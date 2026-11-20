#!/usr/bin/env bash
# =====================================================================================================================
# lint.sh - Jenkins' declarative linter (POST /pipeline-model-converter/validate) over the grocery pipelines.
#
#   bash infrastructure/ci/jenkins/local/lint.sh            # lint
#   bash infrastructure/ci/jenkins/local/lint.sh --load     # lint, then load every job in the running Jenkins
#
# What is validated:
#   1. every shared-library entry point with a `pipeline { }` block (vars/grocery*Pipeline.groovy), as is;
#   2. every Jenkinsfile (services/*, libs/*, frontend/*, infrastructure/ci/jenkins/pipelines/*). A Jenkinsfile only
#      calls a library entry point, so on its own it has no `pipeline { }` block for the declarative linter to check.
#      It is checked for @Library('grocery') and a known entry point, then linted TOGETHER with that entry point
#      (entry point source + the Jenkinsfile's call): the declarative pipeline that job really runs, and the Groovy
#      syntax of the Jenkinsfile itself;
#   3. --load: seeds the jobs, then runs every job with VALIDATE_ONLY=true - Jenkins loads the Jenkinsfile and the
#      shared library from its git repository, validates the configuration (unknown keys, types, required keys) and
#      the declarative model at run time, and runs no stage. Each must end SUCCESS.
#
# Environment: JENKINS_URL (http://localhost:8190), JENKINS_ADMIN_ID (admin), JENKINS_ADMIN_PASSWORD (required),
#              JENKINS_CONTAINER (grocery-ci-jenkins: the library is re-synced from the workspace first).
# Needs bash, curl, sed, grep (Git Bash is fine). Exit code: number of failures (0 = all passed).
# =====================================================================================================================
set -uo pipefail
cd "$(dirname "$0")/../../../.."
ROOT="$(pwd)"
URL="${JENKINS_URL:-http://localhost:8190}"
USER_ID="${JENKINS_ADMIN_ID:-admin}"
PASS="${JENKINS_ADMIN_PASSWORD:?set JENKINS_ADMIN_PASSWORD}"
CONTAINER="${JENKINS_CONTAINER:-grocery-ci-jenkins}"
LIB="infrastructure/ci/jenkins/shared-library"
LOAD=0; [ "${1:-}" = "--load" ] && LOAD=1

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
JAR="$WORK/cookies"
failures=0
passed=0

crumb() {
  curl -fsS -u "$USER_ID:$PASS" -c "$JAR" "$URL/crumbIssuer/api/json" | sed -n 's/.*"crumb":"\([^"]*\)".*/\1/p'
}
CRUMB="$(crumb)" || { echo "lint: cannot reach $URL (is the local Jenkins up? docker compose up -d)"; exit 99; }
[ -n "$CRUMB" ] || { echo "lint: no CSRF crumb from $URL"; exit 99; }

post() { # path, extra curl args...
  local path="$1"; shift
  curl -sS -u "$USER_ID:$PASS" -b "$JAR" -H "Jenkins-Crumb: $CRUMB" -X POST "$@" "$URL$path"
}

validate() { # label file
  local out
  # the file goes through stdin: no path translation issue with a native curl under Git Bash
  out="$(post /pipeline-model-converter/validate -F "jenkinsfile=<-" < "$2")"
  if printf '%s' "$out" | grep -q 'successfully validated'; then
    printf '  PASS  %s\n' "$1"; passed=$((passed + 1))
  else
    printf '  FAIL  %s\n%s\n' "$1" "$(printf '%s' "$out" | sed 's/^/        /')"; failures=$((failures + 1))
  fi
}

if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  docker exec "$CONTAINER" sync-shared-library.sh >/dev/null && echo "shared library re-synced into $CONTAINER"
fi

echo "0) shared-library helpers and classes: Groovy syntax (parsed by the same endpoint; no pipeline block expected)"
for f in "$LIB"/vars/*.groovy $(find "$LIB/src" -name '*.groovy'); do
  grep -qE '^[[:space:]]*pipeline[[:space:]]*\{' "$f" && continue
  out="$(post /pipeline-model-converter/validate -F "jenkinsfile=<-" < "$f")"
  if printf '%s' "$out" | grep -q "did not contain the 'pipeline' step"; then
    printf '  PASS  %s (syntax)\n' "${f#"$LIB"/}"; passed=$((passed + 1))
  else
    printf '  FAIL  %s\n%s\n' "${f#"$LIB"/}" "$(printf '%s' "$out" | sed 's/^/        /' | head -20)"; failures=$((failures + 1))
  fi
done

echo "1) shared-library pipelines ($LIB/vars)"
for f in "$LIB"/vars/*.groovy; do
  grep -qE '^[[:space:]]*pipeline[[:space:]]*\{' "$f" || continue
  validate "vars/$(basename "$f")" "$f"
done

echo "2) Jenkinsfiles (each with the library entry point it calls)"
JENKINSFILES=$(ls services/*/Jenkinsfile libs/*/Jenkinsfile frontend/*/Jenkinsfile infrastructure/ci/jenkins/pipelines/*/Jenkinsfile 2>/dev/null)
for jf in $JENKINSFILES; do
  entry="$(grep -oE 'grocery[A-Za-z]*Pipeline' "$jf" | head -1)"
  if ! grep -qE "^@Library\('grocery'\) _" "$jf"; then
    printf '  FAIL  %s\n        no @Library('"'"'grocery'"'"') _ line\n' "$jf"; failures=$((failures + 1)); continue
  fi
  if [ -z "$entry" ] || [ ! -f "$LIB/vars/$entry.groovy" ]; then
    printf '  FAIL  %s\n        calls no known library entry point (%s)\n' "$jf" "${entry:-none}"; failures=$((failures + 1)); continue
  fi
  eff="$WORK/effective-$(echo "$jf" | tr '/' '_').groovy"
  {
    cat "$LIB/vars/$entry.groovy"
    printf '\n// ---- %s (without the @Library line)\n' "$jf"
    grep -vE "^@Library\('grocery'\) _" "$jf"
  } > "$eff"
  validate "$jf -> $entry" "$eff"
done
total=$((passed + failures))
echo "lint: $passed of $total passed"

if [ "$LOAD" = "1" ]; then
  echo "3) load every job in Jenkins (VALIDATE_ONLY=true)"
  bash "$ROOT/infrastructure/ci/jenkins/local/run-pipeline.sh" seed-jobs >/dev/null || { echo "  FAIL  seed-jobs"; failures=$((failures + 1)); }
  for jf in $JENKINSFILES; do
    case "$jf" in
      infrastructure/ci/jenkins/pipelines/*) job="platform/$(basename "$(dirname "$jf")")" ;;
      *) job="$(dirname "$jf")" ;;
    esac
    result="$(QUIET=1 bash "$ROOT/infrastructure/ci/jenkins/local/run-pipeline.sh" "$job" VALIDATE_ONLY=true 2>&1 | tail -1)"
    case "$result" in
      *SUCCESS*) printf '  PASS  %s (%s)\n' "$job" "$result" ;;
      *) printf '  FAIL  %s (%s)\n' "$job" "$result"; failures=$((failures + 1)) ;;
    esac
  done
fi

exit "$failures"
