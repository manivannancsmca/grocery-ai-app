#!/usr/bin/env bash
# =====================================================================================================================
# validate.sh - offline validation of the AWS Terraform (contract Phase 14 §3). Everything runs in Docker; nothing
# talks to an AWS account (no credentials are used or needed).
#
#   bash infrastructure/aws/validate.sh            (from anywhere; Git Bash on Windows, bash on Linux/macOS)
#
# Steps (each one must pass):
#   1. consistency   env roots identical; catalog JSON valid; topics = local create-topics.sh; connector files exist
#   2. fmt           terraform fmt -check -recursive
#   3. validate      terraform init -backend=false + terraform validate: bootstrap, envs/shared, dev, staging, prod
#   4. test          terraform test -var-file=<env>.tfvars: a full plan of every environment with MOCK providers
#                    (evaluates catalog, tfvars, templates, connector JSONs; asserts the deploy contract)
#   5. tflint        terraform + AWS rulesets (.tflint.hcl): all directories, then each environment with its tfvars
#   6. trivy         trivy config per environment with its tfvars; accepted findings only from .trivyignore
#                    (+ terraform/envs/<env>/.trivyignore), each with a reason and a review date
#   7. observability promtool check rules (AMP alert rules); the pinned ADOT collector loads adot-collector.yaml
#                    (started without network, stopped after 10 s)
#
# Images are pinned; providers, tflint plugins and the Trivy checks bundle are cached in named Docker volumes
# (grocery-tf-plugins, grocery-tflint-plugins, grocery-trivy-cache). The first run downloads them (internet needed).
# Exit code: 0 = all clean, 1 = a step failed.
# =====================================================================================================================
set -uo pipefail

TERRAFORM_IMAGE="hashicorp/terraform:1.17.2"
TFLINT_IMAGE="ghcr.io/terraform-linters/tflint:v0.66.0"
TRIVY_IMAGE="aquasec/trivy:0.76.2"
PROMETHEUS_IMAGE="prom/prometheus:v3.15.1"                                    # promtool
ADOT_IMAGE="public.ecr.aws/aws-observability/aws-otel-collector:v0.51.0"      # same pin as the task definitions
ENVS=(dev staging prod)
ROOTS=(bootstrap envs/shared envs/dev envs/staging envs/prod)

AWS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$AWS_DIR/../.." && pwd)"
TF_DIR="$AWS_DIR/terraform"
WORK="$AWS_DIR/.validate"          # scratch output (git-ignored), inside the mount so containers can write it
IN_TF="/w/infrastructure/aws/terraform"
mkdir -p "$WORK"

export MSYS_NO_PATHCONV=1           # Git Bash: do not rewrite /w/... container paths
export MSYS2_ARG_CONV_EXCL="*"

declare -a SUMMARY=()
FAILED=0
record() {   # record <step> <PASS|FAIL> <detail>
  SUMMARY+=("$(printf '%-12s %-5s %s' "$1" "$2" "$3")")
  [ "$2" = "PASS" ] || FAILED=1
}
section() { printf '\n==== %s\n' "$*"; }

docker_tf() {   # docker_tf <workdir inside terraform/> <terraform args...>
  local dir="$1"; shift
  docker run --rm -v "$REPO_ROOT:/w" -w "$IN_TF/$dir" \
    -v grocery-tf-plugins:/plugin-cache -e TF_PLUGIN_CACHE_DIR=/plugin-cache -e TF_IN_AUTOMATION=1 \
    -e AWS_EC2_METADATA_DISABLED=true \
    "$TERRAFORM_IMAGE" "$@"
}

command -v docker >/dev/null 2>&1 || { echo "docker is required"; exit 1; }

# ------------------------------------------------------------------------------------------------- 1. consistency
section "1. consistency checks"
problems=()

for f in main.tf variables.tf outputs.tf versions.tf plan.tftest.hcl; do
  for e in staging prod; do
    cmp -s "$TF_DIR/envs/dev/$f" "$TF_DIR/envs/$e/$f" || problems+=("envs/$e/$f differs from envs/dev/$f (env roots must be identical)")
  done
done
for e in "${ENVS[@]}"; do
  [ -f "$TF_DIR/envs/$e/$e.tfvars" ] || problems+=("envs/$e/$e.tfvars missing")
  [ -f "$TF_DIR/envs/$e/backend.hcl" ] || problems+=("envs/$e/backend.hcl missing")
done

CATALOG="$TF_DIR/services.auto.tfvars.json"
LOCAL_TOPICS_SCRIPT="$REPO_ROOT/infrastructure/local/kafka/create-topics.sh"
local_topics="$(sed -n '/^DOMAIN_TOPICS=(/,/^)/p' "$LOCAL_TOPICS_SCRIPT" | sed '1d;$d' | tr -s ' \t' '\n' | grep -v '^$' | sort)"
catalog_topics="$(sed -n '/"domain_topics"/,/\]/p' "$CATALOG" | grep -o '"[A-Za-z0-9._-]*\.events"' | tr -d '"' | sort)"
[ -n "$local_topics" ] && [ "$local_topics" = "$catalog_topics" ] \
  || problems+=("kafka_topics.domain_topics differ from DOMAIN_TOPICS in infrastructure/local/kafka/create-topics.sh")
for t in $(grep -o -- '--topic [A-Za-z0-9._-]*' "$LOCAL_TOPICS_SCRIPT" | awk '{print $2}' | grep -v '^\$' | sort -u); do
  grep -q "\"$t\"" "$CATALOG" || problems+=("topic $t of create-topics.sh is missing from kafka_topics.extra_topics")
done

for ref in $(grep -o '"debezium_connector": "[^"]*"' "$CATALOG" | cut -d'"' -f4); do
  [ -f "$REPO_ROOT/$ref" ] || problems+=("debezium_connector $ref does not exist")
done
for f in "$REPO_ROOT"/services/*/deploy/debezium-connector.json; do
  rel="${f#"$REPO_ROOT"/}"
  grep -q "\"$rel\"" "$CATALOG" || problems+=("$rel is not referenced by services.auto.tfvars.json (no MSK Connect connector)")
done

if [ ${#problems[@]} -eq 0 ]; then
  echo "env roots identical; $(echo "$catalog_topics" | wc -l | tr -d ' ') domain topics match create-topics.sh; connector files referenced"
  record consistency PASS "env roots, topics, connector files"
else
  printf '  - %s\n' "${problems[@]}"
  record consistency FAIL "${#problems[@]} problem(s)"
fi

# --------------------------------------------------------------------------------------------------------- 2. fmt
section "2. terraform fmt -check -recursive"
if out="$(docker_tf . fmt -check -recursive -diff 2>&1)"; then
  record fmt PASS "all files formatted"
else
  echo "$out"
  record fmt FAIL "unformatted files (run: terraform fmt -recursive)"
fi

# ------------------------------------------------------------------------------------------------- 3. validate
section "3. terraform init -backend=false + validate"
for root in "${ROOTS[@]}"; do
  if ! out="$(docker_tf "$root" init -backend=false -input=false -no-color 2>&1)"; then
    echo "$out"; record validate FAIL "$root: init failed"; continue
  fi
  if out="$(docker_tf "$root" validate -no-color 2>&1)"; then
    echo "$root: $(echo "$out" | tail -1)"
    record validate PASS "$root"
  else
    echo "$out"; record validate FAIL "$root"
  fi
done

# ----------------------------------------------------------------------------------------------------- 4. test
section "4. terraform test (offline plan with mock providers)"
for e in "${ENVS[@]}"; do
  if out="$(docker_tf "envs/$e" test -var-file="$e.tfvars" -no-color 2>&1)"; then
    echo "$e: $(echo "$out" | tail -1)"
    record test PASS "envs/$e: mock plan + deploy-contract assertions"
  else
    echo "$out"; record test FAIL "envs/$e"
  fi
done

# ---------------------------------------------------------------------------------------------------- 5. tflint
section "5. tflint (terraform + aws rulesets)"
TFLINT_CONFIG="/w/infrastructure/aws/.tflint.hcl"
docker_tflint() {   # docker_tflint <tflint args...>
  docker run --rm -v "$REPO_ROOT:/w" -w "$IN_TF" -v grocery-tflint-plugins:/root/.tflint.d/plugins \
    --entrypoint tflint "$TFLINT_IMAGE" --config="$TFLINT_CONFIG" "$@"
}
if ! out="$(docker_tflint --init 2>&1)"; then
  echo "$out"; record tflint FAIL "plugin installation failed"
else
  if out="$(docker_tflint --recursive --format=compact --minimum-failure-severity=notice 2>&1)"; then
    record tflint PASS "all directories: 0 issues"
  else
    echo "$out"; record tflint FAIL "all directories: $(echo "$out" | grep -c ': \(Error\|Warning\|Notice\) - ') issue(s)"
  fi
  for e in "${ENVS[@]}"; do
    if out="$(docker_tflint --chdir="envs/$e" --var-file="$e.tfvars" --format=compact --minimum-failure-severity=notice 2>&1)"; then
      record tflint PASS "envs/$e with $e.tfvars: 0 issues"
    else
      echo "$out"; record tflint FAIL "envs/$e with $e.tfvars: $(echo "$out" | grep -c ': \(Error\|Warning\|Notice\) - ') issue(s)"
    fi
  done
fi

# ----------------------------------------------------------------------------------------------------- 6. trivy
section "6. trivy config (misconfigurations, per environment)"
for e in "${ENVS[@]}"; do
  ignore="$WORK/trivyignore-$e"
  cat "$AWS_DIR/.trivyignore" > "$ignore"
  [ -f "$TF_DIR/envs/$e/.trivyignore" ] && cat "$TF_DIR/envs/$e/.trivyignore" >> "$ignore"
  accepted="$(grep -c '^[A-Z]' "$ignore")"
  skips=()
  for o in "${ENVS[@]}"; do [ "$o" = "$e" ] || skips+=(--skip-dirs "envs/$o"); done
  docker run --rm -v "$REPO_ROOT:/w" -w "$IN_TF" -v grocery-trivy-cache:/root/.cache/trivy "$TRIVY_IMAGE" \
    config --quiet --tf-vars "envs/$e/$e.tfvars" "${skips[@]}" \
    --ignorefile "/w/infrastructure/aws/.validate/trivyignore-$e" \
    --format json --output "/w/infrastructure/aws/.validate/trivy-$e.json" . >"$WORK/trivy-$e.log" 2>&1
  rc=$?
  if [ $rc -ne 0 ] || [ ! -s "$WORK/trivy-$e.json" ]; then
    cat "$WORK/trivy-$e.log"; record trivy FAIL "envs/$e: scan error"; continue
  fi
  fails="$(grep -c '"Status": "FAIL"' "$WORK/trivy-$e.json")"
  if [ "$fails" -eq 0 ]; then
    record trivy PASS "envs/$e (+ bootstrap, shared): 0 findings, $accepted accepted in .trivyignore"
  else
    docker run --rm -v "$REPO_ROOT:/w" -w "$IN_TF" -v grocery-trivy-cache:/root/.cache/trivy "$TRIVY_IMAGE" \
      config --quiet --tf-vars "envs/$e/$e.tfvars" "${skips[@]}" \
      --ignorefile "/w/infrastructure/aws/.validate/trivyignore-$e" --format table . 2>&1
    record trivy FAIL "envs/$e: $fails finding(s) not accepted"
  fi
done

# ------------------------------------------------------------------------------------- 7. observability configs
section "7. observability configs (AMP rules, ADOT collector config)"
OBS="$IN_TF/modules/observability"
if out="$(docker run --rm -v "$REPO_ROOT:/w" --entrypoint promtool "$PROMETHEUS_IMAGE" check rules "$OBS/grocery-alerts.yml" 2>&1)"; then
  record observability PASS "promtool: $(echo "$out" | grep -o '[0-9]* rules found')"
else
  echo "$out"; record observability FAIL "promtool check rules"
fi

# The collector is started with the real config in a container without network (no AWS call is possible; the
# credentials are dummies the sigv4 extension needs to initialise) and must still be running after 10 s.
ADOT_CONTAINER="grocery-validate-adot-$$"
trap 'docker rm -f "$ADOT_CONTAINER" >/dev/null 2>&1 || true' EXIT
docker run -d --rm --network none --name "$ADOT_CONTAINER" \
  -e AOT_CONFIG_CONTENT="$(cat "$TF_DIR/modules/observability/adot-collector.yaml")" \
  -e AWS_ACCESS_KEY_ID=OFFLINE-VALIDATION-ONLY -e AWS_SECRET_ACCESS_KEY=not-a-real-secret -e AWS_REGION=ap-south-1 \
  -e APP_PORT=8110 -e METRICS_PATH=/actuator/prometheus -e GROCERY_ENV=dev -e SERVICE_NAME=order-service \
  -e AMP_REMOTE_WRITE_ENDPOINT=https://aps-workspaces.ap-south-1.amazonaws.com/workspaces/ws-validate/api/v1/remote_write \
  "$ADOT_IMAGE" >/dev/null 2>&1
sleep 10
adot_logs="$(docker logs "$ADOT_CONTAINER" 2>&1 || true)"
if [ -n "$(docker ps -q --filter "name=$ADOT_CONTAINER")" ] && ! echo "$adot_logs" | grep -qE '^Error|invalid configuration|deprecated'; then
  record observability PASS "ADOT $(echo "$ADOT_IMAGE" | cut -d: -f2) loads adot-collector.yaml (all pipelines start)"
else
  echo "$adot_logs" | grep -v 'Failed to scrape' | tail -20
  record observability FAIL "ADOT collector rejected adot-collector.yaml"
fi
docker rm -f "$ADOT_CONTAINER" >/dev/null 2>&1 || true

# --------------------------------------------------------------------------------------------------- summary
section "summary"
printf '%s\n' "${SUMMARY[@]}"
if [ $FAILED -eq 0 ]; then
  echo "RESULT: all checks passed"
  exit 0
fi
echo "RESULT: FAILED"
exit 1
