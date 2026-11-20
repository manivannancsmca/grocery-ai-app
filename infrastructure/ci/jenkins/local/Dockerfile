# syntax=docker/dockerfile:1.7
# =====================================================================================================================
# Local validation Jenkins (contract Phase 14 §6): the pinned LTS controller + pinned plugins (plugins.txt), the Docker
# CLI and buildx (the pipelines run their steps in sibling containers through the host's Docker socket), python3
# (render-task-definition.py, module-info.py, deploy-spa.sh's JSON edits) and jq. Configuration as code: casc/.
# Not a production Jenkins: builds run on the controller itself (label "docker") and AWS calls are DRY_RUN only.
# =====================================================================================================================
FROM docker:29.9.2-cli AS docker-cli

FROM jenkins/jenkins:2.580.2-lts-jdk21

USER root
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 jq \
 && rm -rf /var/lib/apt/lists/*
# Static binaries: the Docker CLI and its buildx plugin (BuildKit builds)
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx
COPY --chmod=0755 bin/grocery-entrypoint.sh /usr/local/bin/grocery-entrypoint.sh
COPY --chmod=0755 bin/sync-shared-library.sh /usr/local/bin/sync-shared-library.sh

USER jenkins
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt --latest false \
 && jenkins-plugin-cli --list 2>/dev/null | grep -c ' ' | xargs echo "plugins installed:"

# - no setup wizard (JCasC configures everything); the shared library is served from a local git repository
#   (sync-shared-library.sh) because the workspace itself is not a git repository - the git plugin refuses local
#   paths unless ALLOW_LOCAL_CHECKOUT is set (fine for this local, single-user Jenkins only).
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true -Xmx768m -XX:+UseSerialGC"

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/grocery-entrypoint.sh"]
