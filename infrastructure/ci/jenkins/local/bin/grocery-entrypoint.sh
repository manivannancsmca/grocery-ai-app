#!/bin/bash
# Entry point of the local validation Jenkins: refresh the shared library's local git repository from the mounted
# workspace, then start Jenkins as the base image does.
set -euo pipefail
/usr/local/bin/sync-shared-library.sh || echo "grocery-entrypoint: WARNING - shared library not synced"
exec /usr/local/bin/jenkins.sh "$@"
