#!/usr/bin/env bash
# =====================================================================================================================
# deploy-spa.sh - publish the web app (Vite build) to S3 + CloudFront, or roll it back.  Contract Phase 14 §5 and §6.
#
# Usage:
#   deploy-spa.sh <env> <sha> [<dist-dir>]   upload <dist-dir> (default frontend/grocery-web/dist) to
#                                            s3://<bucket>/releases/<sha>/, point the CloudFront origin path at it,
#                                            invalidate, and record the release
#   deploy-spa.sh --rollback <env> [<sha>]   point CloudFront back at an already uploaded release: <sha>, or the
#                                            release deployed before the one being served (rollback.sh <env> frontend
#                                            calls this)
#   <env> is dev | staging | prod.  <sha> is the git commit of the build (the pipeline passes the full SHA).
#
# Bucket layout (the distribution's S3 origin path selects one release; nothing outside it is reachable publicly):
#   releases/<sha>/index.html            Cache-Control: no-cache
#   releases/<sha>/assets/*              Cache-Control: public, max-age=31536000, immutable (content-hashed names)
#   releases/<sha>/<other root files>    Cache-Control: no-cache (favicon.svg ...)
#   meta/release-history.tsv             one line per switch: time, action (deploy|rollback), sha, actor
#
# Environment:
#   DRY_RUN=1             print every aws call instead of running it: no credentials, no network. Read calls return
#                         sample data, so the whole flow (including the origin-path edit) is exercised.
#   SPA_BUCKET            bucket name          (default: SSM parameter /grocery/<env>/frontend/bucket-name)
#   SPA_DISTRIBUTION_ID   distribution id      (default: SSM parameter /grocery/<env>/frontend/distribution-id)
#   SPA_ORIGIN_ID         CloudFront origin id of the bucket (default: the one origin whose domain is an S3 bucket)
#   WAIT=0                do not wait for the distribution to deploy / the invalidation to complete (default: wait)
#   AWS_REGION, AWS_PROFILE ...   as usual for the aws CLI (CloudFront itself is global)
#
# Needs: bash, aws CLI v2, and jq or python3 (to edit the distribution config).  Exit codes: 0 ok, 1 failed, 2 usage.
# =====================================================================================================================
set -euo pipefail
shopt -s inherit_errexit   # errors inside $(...) stop the script too

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
HISTORY_KEY="meta/release-history.tsv"
IMMUTABLE_CACHE="public, max-age=31536000, immutable"
REVALIDATE_CACHE="no-cache"

DRY_RUN="${DRY_RUN:-0}"
WAIT="${WAIT:-1}"

# ------------------------------------------------------------------------------------------------------- helpers
log()  { printf '[deploy-spa] %s\n' "$*" >&2; }
die()  { printf '[deploy-spa] ERROR: %s\n' "$*" >&2; exit 1; }
usage() { sed -n '4,12p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
is_dry() { [ "$DRY_RUN" = "1" ] || [ "$DRY_RUN" = "true" ]; }

# Prints a command the way it would be typed (single-quoting only what needs it).
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

# A read call whose output the script needs. $1 is the sample output used in a dry run.
read_call() {
  local sample="$1"; shift
  if is_dry; then printf 'DRY-RUN: %s\n' "$(show_cmd "$@")" >&2; printf '%s\n' "$sample"; return 0; fi
  "$@"
}

require_env_name() {
  case "$1" in dev|staging|prod) ;; *) die "unknown environment '$1' (expected dev, staging or prod)";; esac
}

require_release_id() {
  # A git SHA in practice; anything safe as an S3 prefix and a CloudFront origin path is accepted.
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{5,63}$ ]] || die "invalid release id '$1' (expected a git SHA)"
}

# --------------------------------------------------------------------------------------------- JSON (jq or python)
JSON_TOOL=""
pick_json_tool() {
  if command -v jq >/dev/null 2>&1; then JSON_TOOL=jq; return; fi
  local py
  for py in python3 python; do
    if command -v "$py" >/dev/null 2>&1 && "$py" -c 'import json, re, sys' >/dev/null 2>&1; then JSON_TOOL="$py"; return; fi
  done
  die "jq or python3 is required to edit the CloudFront distribution config"
}

# The bucket origin: SPA_ORIGIN_ID when given, otherwise the only origin with an S3 REST endpoint as its domain.
JQ_SPA_ORIGIN='if $id != "" then .Id == $id else (.DomainName | test("\\.s3\\.([a-z0-9-]+\\.)?amazonaws\\.com$")) end'
PY_SPA_ORIGIN='
import json, re, sys
def spa_origins(cfg, origin_id):
    items = cfg["Origins"].get("Items") or []
    if origin_id:
        return [o for o in items if o["Id"] == origin_id]
    return [o for o in items if re.search(r"\.s3\.([a-z0-9-]+\.)?amazonaws\.com$", o["DomainName"])]
'

# get-distribution-config output (file $1) -> ETag
json_etag() {
  if [ "$JSON_TOOL" = jq ]; then jq -er '.ETag' "$1"
  else "$JSON_TOOL" -c 'import json,sys; print(json.load(open(sys.argv[1]))["ETag"])' "$1"; fi
}

# get-distribution-config output (file $1) -> current origin path of the SPA origin ("" when none)
json_spa_origin_path() {
  if [ "$JSON_TOOL" = jq ]; then
    jq -er --arg id "${SPA_ORIGIN_ID:-}" "[.DistributionConfig.Origins.Items[] | select($JQ_SPA_ORIGIN)]
      | if length == 1 then (.[0].OriginPath // \"\") else error(\"expected exactly one S3 origin, found \\(length) (set SPA_ORIGIN_ID)\") end" "$1"
  else
    "$JSON_TOOL" -c "$PY_SPA_ORIGIN
found = spa_origins(json.load(open(sys.argv[1]))['DistributionConfig'], sys.argv[2])
if len(found) != 1:
    sys.exit('expected exactly one S3 origin, found %d (set SPA_ORIGIN_ID)' % len(found))
print(found[0].get('OriginPath') or '')" "$1" "${SPA_ORIGIN_ID:-}"
  fi
}

# get-distribution-config output (file $1), release id ($2) -> the DistributionConfig with the S3 origin path set to
# /releases/<id>, to send back (stdout). Only the id is passed (no slash), so no shell/MSYS layer rewrites it as a path.
json_with_release_origin_path() {
  if [ "$JSON_TOOL" = jq ]; then
    jq --arg id "${SPA_ORIGIN_ID:-}" --arg rel "$2" \
      ".DistributionConfig | .Origins.Items |= map(if ($JQ_SPA_ORIGIN) then .OriginPath = (\"/releases/\" + \$rel) else . end)" "$1"
  else
    "$JSON_TOOL" -c "$PY_SPA_ORIGIN
cfg = json.load(open(sys.argv[1]))['DistributionConfig']
for origin in spa_origins(cfg, sys.argv[2]):
    origin['OriginPath'] = '/releases/' + sys.argv[3]
json.dump(cfg, sys.stdout, indent=2)" "$1" "${SPA_ORIGIN_ID:-}" "$2"
  fi
}

# ----------------------------------------------------------------------------------------------- dry-run samples
sample_distribution_config() {
  local current="${DRY_RUN_CURRENT_RELEASE:-1111111111111111111111111111111111111111}"
  cat <<EOF
{
  "ETag": "E2SAMPLEETAG",
  "DistributionConfig": {
    "CallerReference": "terraform-sample",
    "Comment": "grocery-$ENV_NAME-spa (dry-run sample)",
    "Enabled": true,
    "Origins": {
      "Quantity": 2,
      "Items": [
        { "Id": "spa-s3", "DomainName": "$BUCKET.s3.${AWS_REGION:-ap-south-1}.amazonaws.com",
          "OriginPath": "/releases/$current", "OriginAccessControlId": "E2SAMPLEOAC",
          "S3OriginConfig": { "OriginAccessIdentity": "" } },
        { "Id": "api-alb", "DomainName": "grocery-$ENV_NAME-alb-123456.${AWS_REGION:-ap-south-1}.elb.amazonaws.com",
          "OriginPath": "", "CustomOriginConfig": { "HTTPPort": 80, "HTTPSPort": 443, "OriginProtocolPolicy": "https-only" } }
      ]
    }
  }
}
EOF
}

sample_history() {
  local current="${DRY_RUN_CURRENT_RELEASE:-1111111111111111111111111111111111111111}"
  printf '2026-11-01T10:00:00Z\tdeploy\t%s\tjenkins\n' "0000000000000000000000000000000000000000"
  printf '2026-11-05T10:00:00Z\tdeploy\t%s\tjenkins\n' "$current"
}

# -------------------------------------------------------------------------------------------------- AWS steps
resolve_targets() {
  BUCKET="${SPA_BUCKET:-}"
  DISTRIBUTION_ID="${SPA_DISTRIBUTION_ID:-}"
  if [ -z "$BUCKET" ]; then
    BUCKET="$(read_call "grocery-$ENV_NAME-spa-000000000000" aws ssm get-parameter \
      --name "/grocery/$ENV_NAME/frontend/bucket-name" --query Parameter.Value --output text)"
  fi
  if [ -z "$DISTRIBUTION_ID" ]; then
    DISTRIBUTION_ID="$(read_call "E1SAMPLEDISTID" aws ssm get-parameter \
      --name "/grocery/$ENV_NAME/frontend/distribution-id" --query Parameter.Value --output text)"
  fi
  [ -n "$BUCKET" ] && [ "$BUCKET" != "None" ] || die "no bucket (set SPA_BUCKET or SSM /grocery/$ENV_NAME/frontend/bucket-name)"
  [ -n "$DISTRIBUTION_ID" ] && [ "$DISTRIBUTION_ID" != "None" ] || die "no distribution id (set SPA_DISTRIBUTION_ID)"
  log "environment $ENV_NAME: bucket s3://$BUCKET, distribution $DISTRIBUTION_ID"
}

fetch_distribution_config() {
  read_call "$(sample_distribution_config)" \
    aws cloudfront get-distribution-config --id "$DISTRIBUTION_ID" --output json > "$WORK/distribution.json"
}

current_release() {
  fetch_distribution_config
  local path
  path="$(json_spa_origin_path "$WORK/distribution.json")" || die "cannot find the S3 origin of $DISTRIBUTION_ID"
  case "$path" in
    /releases/*) printf '%s\n' "${path#/releases/}" ;;
    *) printf '\n' ;;   # not on a release yet (first deployment)
  esac
}

release_exists() {
  if is_dry; then
    read_call "{}" aws s3api head-object --bucket "$BUCKET" --key "releases/$1/index.html" >/dev/null
    return 0
  fi
  aws s3api head-object --bucket "$BUCKET" --key "releases/$1/index.html" >/dev/null 2>&1
}

upload_release() {
  local dist="$1" prefix="s3://$BUCKET/releases/$RELEASE"
  log "uploading $dist -> $prefix/ ($(find "$dist" -type f | wc -l | tr -d ' ') files)"
  run aws s3 sync "$dist/assets" "$prefix/assets" \
    --cache-control "$IMMUTABLE_CACHE" --only-show-errors --no-progress
  run aws s3 sync "$dist" "$prefix" --exclude "assets/*" --exclude "index.html" \
    --cache-control "$REVALIDATE_CACHE" --only-show-errors --no-progress
  # index.html last: a release is complete once its entry point exists.
  run aws s3 cp "$dist/index.html" "$prefix/index.html" \
    --cache-control "$REVALIDATE_CACHE" --content-type "text/html; charset=utf-8" --only-show-errors --no-progress
}

# Sets SWITCHED=1 when the origin path was changed. (Not a return status: a function called as an "if" condition
# runs without errexit, so a failing aws call inside it would go unnoticed.)
SWITCHED=0
switch_to_release() {
  local target="/releases/$1" current_path
  SWITCHED=0
  # The config fetched by current_release() is reused: its ETag makes the update fail if anyone changed it since.
  current_path="$(json_spa_origin_path "$WORK/distribution.json")"
  if [ "$current_path" = "$target" ]; then
    log "CloudFront already serves $target; nothing to switch"
    return 0
  fi
  local etag
  etag="$(json_etag "$WORK/distribution.json")"
  json_with_release_origin_path "$WORK/distribution.json" "$1" > "$WORK/distribution-new.json"
  log "origin path: '${current_path:-<none>}' -> '$target'"
  if is_dry; then
    printf 'DRY-RUN: (edited DistributionConfig, S3 origin) %s\n' \
      "$(grep -E '"(Id|OriginPath)"' "$WORK/distribution-new.json" | tr -d ' \n' | sed 's/,$//')"
  fi
  run aws cloudfront update-distribution --id "$DISTRIBUTION_ID" --if-match "$etag" \
    --distribution-config "file://$WORK/distribution-new.json" --query Distribution.Status --output text
  if [ "$WAIT" = "1" ]; then
    run aws cloudfront wait distribution-deployed --id "$DISTRIBUTION_ID"
  fi
  SWITCHED=1
}

invalidate() {
  # Everything: cached index.html and the SPA-fallback responses for deep links came from the previous release.
  # A wildcard counts as one invalidation path.
  local id
  id="$(read_call "I2SAMPLEINVALIDATION" aws cloudfront create-invalidation --distribution-id "$DISTRIBUTION_ID" \
    --paths '/*' --query Invalidation.Id --output text)"
  log "invalidation $id"
  if [ "$WAIT" = "1" ]; then
    run aws cloudfront wait invalidation-completed --distribution-id "$DISTRIBUTION_ID" --id "$id"
  fi
}

load_history() {
  if is_dry; then
    printf 'DRY-RUN: %s\n' "$(show_cmd aws s3 cp "s3://$BUCKET/$HISTORY_KEY" "$WORK/history.tsv" --only-show-errors)" >&2
    if [ -n "${DRY_RUN_HISTORY_FILE:-}" ]; then cp "$DRY_RUN_HISTORY_FILE" "$WORK/history.tsv"; else sample_history > "$WORK/history.tsv"; fi
  elif aws s3api head-object --bucket "$BUCKET" --key "$HISTORY_KEY" >/dev/null 2>&1; then
    aws s3 cp "s3://$BUCKET/$HISTORY_KEY" "$WORK/history.tsv" --only-show-errors
  else
    : > "$WORK/history.tsv"
  fi
}

record_release() {
  local action="$1" sha="$2"
  load_history
  printf '%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$action" "$sha" \
    "${BUILD_TAG:-${USER:-${USERNAME:-unknown}}}" >> "$WORK/history.tsv"
  run aws s3 cp "$WORK/history.tsv" "s3://$BUCKET/$HISTORY_KEY" \
    --content-type "text/tab-separated-values; charset=utf-8" --cache-control "no-store" --only-show-errors
}

# The release deployed before $1: the newest "deploy" line older than the newest deploy of $1. When $1 is not in
# the history (the origin was changed by hand), the newest deployed release that is not $1.
previous_release() {
  awk -F'\t' -v cur="$1" '
    $2 == "deploy" { n++; sha[n] = $3 }
    END {
      last = 0
      for (i = n; i >= 1; i--) if (sha[i] == cur) { last = i; break }
      start = (last == 0) ? n : last - 1
      for (i = start; i >= 1; i--) if (sha[i] != cur) { print sha[i]; exit }
    }' "$WORK/history.tsv"
}

# ------------------------------------------------------------------------------------------------------- modes
deploy() {
  local dist="${DIST_DIR:-$REPO_ROOT/frontend/grocery-web/dist}"
  [ -f "$dist/index.html" ] || die "$dist/index.html not found - run 'npm run build' first"
  [ -d "$dist/assets" ] || die "$dist/assets not found - is this a Vite build?"

  resolve_targets
  local current
  current="$(current_release)"
  log "serving now: ${current:-<no release>}; deploying: $RELEASE"
  upload_release "$dist"
  switch_to_release "$RELEASE"
  if [ "$SWITCHED" = "1" ]; then
    invalidate
    record_release deploy "$RELEASE"
  fi
  log "done: $ENV_NAME serves releases/$RELEASE (previous: ${current:-none})"
}

rollback() {
  resolve_targets
  local current target
  current="$(current_release)"
  [ -n "$current" ] || die "the distribution does not serve a release yet; nothing to roll back"
  if [ -n "${RELEASE:-}" ]; then
    target="$RELEASE"
  else
    load_history
    target="$(previous_release "$current")"
    [ -n "$target" ] || die "no earlier release in s3://$BUCKET/$HISTORY_KEY; pass the sha to roll back to"
  fi
  require_release_id "$target"
  [ "$target" != "$current" ] || die "releases/$target is already being served"
  release_exists "$target" || die "s3://$BUCKET/releases/$target/index.html does not exist"
  log "rolling back: $current -> $target"
  switch_to_release "$target"
  if [ "$SWITCHED" = "1" ]; then
    invalidate
    record_release rollback "$target"
  fi
  log "done: serving releases/$target (was $current)"
}

# -------------------------------------------------------------------------------------------------------- main
MODE=deploy
if [ "${1:-}" = "--rollback" ]; then MODE=rollback; shift; fi
case "${1:-}" in -h|--help|"") usage;; esac

ENV_NAME="$1"
require_env_name "$ENV_NAME"
RELEASE="${2:-}"
if [ "$MODE" = deploy ]; then
  [ -n "$RELEASE" ] || usage
  [ -z "${3:-}" ] || DIST_DIR="$3"
fi
[ -z "$RELEASE" ] || require_release_id "$RELEASE"

command -v aws >/dev/null 2>&1 || is_dry || die "the aws CLI is required"
pick_json_tool
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
is_dry && log "DRY RUN: no AWS call is made; read calls return sample data"

"$MODE"
