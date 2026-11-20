#!/usr/bin/env bash
# =====================================================================================================================
# Writes the images this CI run published into the GitOps repository (Kustomize overlays), commits and pushes.
# Argo CD notices the commit and rolls the new images out. CI never talks to a Kubernetes cluster.
#
#   update-gitops.sh <gitops-checkout-dir> <deploy-records-dir>
#
# Each deploy record (one file per image, written by the "images" job) holds one line:
#   <name> <kind> <image-repository> <tag> <digest>
#   e.g. order-service service ghcr.io/owner/grocery-order-service sha-1a2b3c... sha256:...
#
# Kustomize "images:" entries are updated with yq (preinstalled on GitHub's Ubuntu runners). The tag is kept for
# humans; the digest makes the reference immutable (the image Kubernetes runs is exactly the one CI tested and
# scanned).
#
# Environments updated automatically: ENVIRONMENTS (default "kind staging"). Production is promoted with a pull request
# in the GitOps repository (its "promote" workflow), never from here.
# =====================================================================================================================
set -euo pipefail

gitops="${1:?usage: update-gitops.sh <gitops-dir> <records-dir>}"
records="${2:?usage: update-gitops.sh <gitops-dir> <records-dir>}"
environments="${ENVIRONMENTS:-kind staging}"
source_ref="${GITHUB_REPOSITORY:-local}@${GITHUB_SHA:-unknown}"
run_url="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-0}"

overlay_dir() {   # <kind> <name> <env>
  case "$1" in
    service)       echo "services/$2/overlays/$3" ;;
    frontend)      echo "frontend/$2/overlays/$3" ;;
    platform-init) echo "infrastructure/platform-init/overlays/$3" ;;
  esac
}
image_key() {     # the name the base manifests use (images[].name in the overlay)
  case "$1" in
    service)       echo "grocery/$2" ;;
    frontend)      echo "grocery/$2" ;;
    platform-init) echo "grocery/platform-init" ;;
  esac
}

updated=()
shopt -s nullglob
for record in "$records"/*.txt; do
  read -r name kind repository tag digest < "$record"
  for env in $environments; do
    dir="$gitops/$(overlay_dir "$kind" "$name" "$env")"
    file="$dir/kustomization.yaml"
    if [[ ! -f "$file" ]]; then
      echo "skip $name ($env): $file does not exist"
      continue
    fi
    key="$(image_key "$kind" "$name")"
    KEY="$key" REPO="$repository" TAG="$tag" DIGEST="$digest" yq -i '
      .images = ((.images // []) | map(select(.name != strenv(KEY)))) +
                [{"name": strenv(KEY), "newName": strenv(REPO), "newTag": strenv(TAG), "digest": strenv(DIGEST)}]
    ' "$file"
    updated+=("$name ($env)")
    echo "set $name ($env) -> $repository:$tag@$digest"
  done
done

cd "$gitops"
if git diff --quiet; then
  echo "GitOps repository already up to date."
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
short="${GITHUB_SHA:-unknown}"; short="${short:0:7}"
{
  echo "deploy: ${#updated[@]} image update(s) from ${short}"
  echo
  printf -- '- %s\n' "${updated[@]}"
  echo
  echo "Source: ${source_ref}"
  echo "CI run: ${run_url}"
} > /tmp/gitops-commit-msg.txt
git commit -q -F /tmp/gitops-commit-msg.txt

# Another CI run may have pushed in the meantime: rebase on it and retry (the runs touch different files).
for attempt in 1 2 3 4 5; do
  if git push -q origin HEAD:main; then
    echo "Pushed $(git rev-parse --short HEAD) to the GitOps repository."
    exit 0
  fi
  echo "Push rejected (attempt $attempt); rebasing on the latest main."
  sleep $((attempt * 3))
  git pull -q --rebase origin main
done
echo "Could not push to the GitOps repository after 5 attempts." >&2
exit 1
