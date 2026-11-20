# syntax=docker/dockerfile:1.7
# =====================================================================================================================
# grocery-platform-init: a data-only "bundle" image for the Kubernetes setup Jobs (GitOps repo,
# infrastructure/platform-init). It carries, from THIS repository, everything the one-off setup steps need:
#
#   /bundle/mysql/*.sql                         databases, per-service users, Debezium + monitoring users
#   /bundle/kafka/create-topics.sh              domain topics + dead-letter topics
#   /bundle/schema-registry/register-schemas.sh registers the Avro schemas (BACKWARD_TRANSITIVE)
#   /bundle/schemas/                            libs/grocery-event-schemas/src/main/avro
#   /bundle/debezium/register-connectors.sh     registers the outbox connectors
#   /bundle/services/<svc>/deploy/debezium-connector.json
#
# Each Job copies the bundle into an emptyDir with this image as an init container, then runs the step in the
# tool's own image (mysql, apache/kafka, debezium/connect). So the setup data is versioned with the code (tag =
# commit SHA, set by CI in the GitOps repo) and the same scripts as the local Compose setup are used.
#
# Build context = .ci/platform-init, staged by infrastructure/k8s/platform-init/stage.sh (CI does both):
#   infrastructure/k8s/platform-init/stage.sh .ci/platform-init
#   docker build -f infrastructure/k8s/platform-init/Dockerfile -t grocery-platform-init:dev .ci/platform-init
# =====================================================================================================================
ARG BASE_IMAGE=busybox:1.38.0-musl
FROM ${BASE_IMAGE}
ARG BASE_IMAGE
ARG VERSION=0.0.0-dev
ARG REVISION=unknown
ARG CREATED=unknown
ARG SOURCE=unknown

LABEL org.opencontainers.image.title="grocery-platform-init" \
      org.opencontainers.image.description="Setup data for the grocery Kubernetes Jobs: SQL, Kafka topics, Avro schemas, Debezium connectors" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.source="${SOURCE}" \
      org.opencontainers.image.vendor="grocery" \
      org.opencontainers.image.licenses="NOASSERTION" \
      org.opencontainers.image.base.name="docker.io/library/${BASE_IMAGE}"

# Read-only data owned by root; the copy step only reads it.
COPY --chown=0:0 bundle/ /bundle/
RUN find /bundle -type d -exec chmod 0755 {} + \
 && find /bundle -type f -exec chmod 0644 {} + \
 && find /bundle -name '*.sh' -exec chmod 0755 {} +

# Unprivileged (nobody). Copies the bundle into the shared volume mounted at /work.
USER 65534:65534
ENTRYPOINT ["/bin/sh", "-c", "cp -R /bundle/. /work/ && echo \"bundle copied to /work\" && ls /work"]
