#!/usr/bin/env bash
# =====================================================================================================================
# rollback.sh - manual rollback of one deployable unit in one environment.  Contract Phase 14 §5.
#
# Usage:
#   rollback.sh <env> <service> [<revision>]
#     <env>       dev | staging | prod
#     <service>   an ECS service of services.auto.tfvars.json (api-gateway, order-service, schema-registry ...),
#                 or "frontend" (the SPA on S3 + CloudFront)
#     <revision>  ECS: the task-definition revision to run (41, <family>:41 or the full ARN); default: the newest
#                 ACTIVE revision older than the one the service runs now.
#                 frontend: the release sha; default: the release deployed before the current one.
#
# ECS, step by step:
#   1. read the task definition the service runs now;
#   2. a deployment still in progress (blue/green test traffic or bake time) is stopped with ROLLBACK: ECS moves
#      production traffic back to the blue revision at once. Without an explicit <revision> that is the rollback;
#   3. otherwise update-service to the chosen revision. That is an ordinary blue/green deployment (test listener,
#      bake time, alarm-based automatic rollback) of an image that already ran in this environment;
#   4. wait until the service is stable and check that it runs the chosen revision.
#   Database migrations are never rolled back: expand/contract keeps the older revision compatible with the schema.
#
# Environment:
#   DRY_RUN=1        print every aws call instead of running it: no credentials, no network. Read calls return sample
#                    data; DRY_RUN_CURRENT_REVISION (default 42) and DRY_RUN_IN_PROGRESS=1 shape it.
#   ECS_CLUSTER      default grocery-<env>-cluster
#   ECS_SERVICE      default grocery-<env>-<service>
#   TASK_FAMILY      default: the ECS service name
#   WAIT_TIMEOUT     seconds to wait for a stable service (default 1800; prod bake time is 5 min)
#   AWS_REGION, AWS_PROFILE ...   as usual for the aws CLI.  SPA_* variables are passed on to deploy-spa.sh.
#
# Exit codes: 0 rolled back (or nothing to do), 1 failed, 2 usage.
# =====================================================================================================================
set -euo pipefail
shopt -s inherit_errexit   # errors inside $(...) stop the script too

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DRY_RUN="${DRY_RUN:-0}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-1800}"

log()  { printf '[rollback] %s\n' "$*" >&2; }
die()  { printf '[rollback] ERROR: %s\n' "$*" >&2; exit 1; }
usage() { sed -n '4,12p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
is_dry() { [ "$DRY_RUN" = "1" ] || [ "$DRY_RUN" = "true" ]; }

show_cmd() {
  local out="" arg
  for arg in "$@"; do
    if [[ "$arg" =~ ^[A-Za-z0-9_./:=@%+,-]+$ ]]; then out+="$arg "; else out+="'${arg//\'/\'\\\'\'}' "; fi
  done
  printf '%s' "${out% }"
}

# A call that changes something (or waits): printed in a dry run, executed otherwise.
run() {
  if is_dry; then printf 'DRY-RUN: %s\n' "$(show_cmd "$@")"; return 0; fi
  log "$(show_cmd "$@")"
  "$@"
}

# A read call whose output is needed. $1 is the sample output used in a dry run.
read_call() {
  local sample="$1"; shift
  if is_dry; then printf 'DRY-RUN: %s\n' "$(show_cmd "$@")" >&2; printf '%s\n' "$sample"; return 0; fi
  "$@"
}

# ------------------------------------------------------------------------------------------------------ arguments
case "${1:-}" in -h|--help|"") usage;; esac
[ $# -ge 2 ] && [ $# -le 3 ] || usage
ENV_NAME="$1"
UNIT="$2"
REQUESTED="${3:-}"

case "$ENV_NAME" in dev|staging|prod) ;; *) die "unknown environment '$ENV_NAME' (expected dev, staging or prod)";; esac

# The SPA has its own mechanism (S3 release prefixes behind CloudFront).
if [ "$UNIT" = "frontend" ] || [ "$UNIT" = "grocery-web" ]; then
  log "frontend: re-pointing CloudFront to an earlier release (deploy-spa.sh --rollback)"
  exec bash "$SCRIPT_DIR/deploy-spa.sh" --rollback "$ENV_NAME" ${REQUESTED:+"$REQUESTED"}
fi

[[ "$UNIT" =~ ^[a-z][a-z0-9-]{1,48}$ ]] || die "invalid service name '$UNIT'"
CLUSTER="${ECS_CLUSTER:-grocery-$ENV_NAME-cluster}"
SERVICE="${ECS_SERVICE:-grocery-$ENV_NAME-$UNIT}"
FAMILY="${TASK_FAMILY:-$SERVICE}"
SAMPLE_ARN_PREFIX="arn:aws:ecs:${AWS_REGION:-ap-south-1}:000000000000:task-definition"
SAMPLE_CURRENT="${DRY_RUN_CURRENT_REVISION:-42}"

command -v aws >/dev/null 2>&1 || is_dry || die "the aws CLI is required"
is_dry && log "DRY RUN: no AWS call is made; read calls return sample data"
log "environment $ENV_NAME: cluster $CLUSTER, service $SERVICE, task family $FAMILY"

# ------------------------------------------------------------------------------------------------------- helpers
revision_of() { printf '%s\n' "${1##*:}"; }            # .../family:41 -> 41
family_rev_of() { printf '%s\n' "${1##*/}"; }          # arn:...:task-definition/family:41 -> family:41

# $1 = sample answer for dry runs
current_task_definition() {
  local arn
  arn="$(read_call "$1" aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
    --query 'services[0].taskDefinition' --output text)"
  [ -n "$arn" ] && [ "$arn" != "None" ] || die "service $SERVICE not found in cluster $CLUSTER"
  printf '%s\n' "$arn"
}

in_progress_deployment() {
  local sample=None
  [ "${DRY_RUN_IN_PROGRESS:-0}" = "1" ] && \
    sample="arn:aws:ecs:${AWS_REGION:-ap-south-1}:000000000000:service-deployment/$CLUSTER/$SERVICE/SampleDeployment01"
  read_call "$sample" aws ecs list-service-deployments --cluster "$CLUSTER" --service "$SERVICE" \
    --status IN_PROGRESS PENDING --query 'serviceDeployments[0].serviceDeploymentArn' --output text
}

# The newest ACTIVE revision of $FAMILY that is older than revision $1 (full ARN), or nothing.
previous_revision() {
  local current_rev="$1" sample arn rev
  sample="$(for r in $((SAMPLE_CURRENT + 1)) "$SAMPLE_CURRENT" $((SAMPLE_CURRENT - 1)) $((SAMPLE_CURRENT - 3)); do
    printf '%s/%s:%s\t' "$SAMPLE_ARN_PREFIX" "$FAMILY" "$r"; done)"
  # --family-prefix also matches longer family names: keep exact matches only.
  for arn in $(read_call "$sample" aws ecs list-task-definitions --family-prefix "$FAMILY" --status ACTIVE \
      --sort DESC --max-items 100 --query 'taskDefinitionArns' --output text); do
    [ "$(family_rev_of "$arn" | sed 's/:[0-9]*$//')" = "$FAMILY" ] || continue
    rev="$(revision_of "$arn")"
    if [ "$rev" -lt "$current_rev" ]; then printf '%s\n' "$arn"; return 0; fi
  done
}

# 41 | family:41 | full ARN  ->  family:41 (or the ARN as given)
normalize_requested() {
  if [[ "$1" =~ ^[0-9]+$ ]]; then
    printf '%s:%s\n' "$FAMILY" "$1"
  elif [[ "$1" =~ ^(arn:aws[a-z-]*:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/)?[A-Za-z0-9_-]+:[0-9]+$ ]]; then
    printf '%s\n' "$1"
  else
    die "invalid revision '$1' (expected 41, <family>:41 or a task-definition ARN)"
  fi
}

wait_stable() {
  local deadline=$(( $(date +%s) + WAIT_TIMEOUT ))
  # The waiter gives up after 10 minutes; blue/green with a 5-minute bake can take longer, so it is repeated.
  until run aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"; do
    [ "$(date +%s)" -lt "$deadline" ] || die "$SERVICE is not stable after ${WAIT_TIMEOUT}s - check the ECS console / deployment events"
    log "still converging ..."
  done
}

describe_images() {
  read_call "app	000000000000.dkr.ecr.${AWS_REGION:-ap-south-1}.amazonaws.com/grocery/$UNIT:0.1.0-abc1234
adot-collector	public.ecr.aws/aws-observability/aws-otel-collector:<pinned>" \
    aws ecs describe-task-definition --task-definition "$1" \
    --query 'taskDefinition.containerDefinitions[].[name,image]' --output text | sed 's/^/    /' >&2
}

# ---------------------------------------------------------------------------------------------------------- main
CURRENT="$(current_task_definition "$SAMPLE_ARN_PREFIX/$FAMILY:$SAMPLE_CURRENT")"
log "running now: $(family_rev_of "$CURRENT")"

DEPLOYMENT="$(in_progress_deployment)"
if [ -n "$DEPLOYMENT" ] && [ "$DEPLOYMENT" != "None" ]; then
  log "a deployment is in progress ($DEPLOYMENT): stopping it with ROLLBACK"
  run aws ecs stop-service-deployment --service-deployment-arn "$DEPLOYMENT" --stop-type ROLLBACK \
    --query serviceDeploymentArn --output text
  wait_stable
  CURRENT="$(current_task_definition "$SAMPLE_ARN_PREFIX/$FAMILY:$((SAMPLE_CURRENT - 1))")"
  log "after the stopped deployment: $(family_rev_of "$CURRENT")"
  if [ -z "$REQUESTED" ]; then
    log "done: the in-progress deployment was rolled back; $SERVICE runs $(family_rev_of "$CURRENT")"
    exit 0
  fi
fi

if [ -n "$REQUESTED" ]; then
  TARGET="$(normalize_requested "$REQUESTED")"
else
  TARGET="$(previous_revision "$(revision_of "$CURRENT")")"
  [ -n "$TARGET" ] || die "no ACTIVE revision of $FAMILY older than $(revision_of "$CURRENT"); pass one explicitly"
fi

if [ "$(family_rev_of "$TARGET")" = "$(family_rev_of "$CURRENT")" ]; then
  log "$SERVICE already runs $(family_rev_of "$TARGET"); nothing to do"
  exit 0
fi

log "rolling back $SERVICE: $(family_rev_of "$CURRENT") -> $(family_rev_of "$TARGET"); containers of the target:"
describe_images "$TARGET"
run aws ecs update-service --cluster "$CLUSTER" --service "$SERVICE" --task-definition "$TARGET" \
  --query service.taskDefinition --output text
wait_stable

NOW="$(current_task_definition "$SAMPLE_ARN_PREFIX/$(family_rev_of "$TARGET")")"
[ "$(family_rev_of "$NOW")" = "$(family_rev_of "$TARGET")" ] \
  || die "$SERVICE runs $(family_rev_of "$NOW"), not $(family_rev_of "$TARGET") - the deployment was probably rolled back automatically (alarms / circuit breaker); check the deployment events"
log "done: $SERVICE runs $(family_rev_of "$NOW") (was $(family_rev_of "$CURRENT"))"
