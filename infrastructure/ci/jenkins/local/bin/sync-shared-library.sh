#!/bin/bash
# =====================================================================================================================
# sync-shared-library.sh - copies infrastructure/ci/jenkins/shared-library from the mounted repository (read-only,
# not a git repository) into a git repository inside JENKINS_HOME, which the global library "grocery" is retrieved
# from (casc/jenkins.yaml). Runs at container start; after editing the library run it again:
#   docker exec grocery-ci-jenkins sync-shared-library.sh
# (lint.sh and run-pipeline.sh do that for you). Every pipeline run fetches the library afresh (no caching).
# =====================================================================================================================
set -euo pipefail
SRC="${GROCERY_LOCAL_REPO:-/repo}/infrastructure/ci/jenkins/shared-library"
DST="${JENKINS_HOME:-/var/jenkins_home}/local-scm/grocery-shared-library"
[ -d "$SRC/vars" ] || { echo "sync-shared-library: $SRC/vars not found (is the repository mounted at ${GROCERY_LOCAL_REPO:-/repo}?)" >&2; exit 1; }

mkdir -p "$DST"
cd "$DST"
[ -d .git ] || git init -q -b main
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -R "$SRC"/. .
git add -A
if git -c user.name="grocery local CI" -c user.email="ci@grocery.local" commit -q -m "sync from $SRC at $(date -u +%Y-%m-%dT%H:%M:%SZ)"; then
  echo "sync-shared-library: new revision $(git rev-parse --short HEAD) ($(git ls-files | wc -l) files)"
else
  echo "sync-shared-library: unchanged ($(git rev-parse --short HEAD))"
fi
