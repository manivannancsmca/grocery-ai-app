# Prometheus JMX exporter (standalone) for kafka-connect (Debezium) — compose profile "observability".
#
# Why a separate container: the Debezium image's ENABLE_JMX_EXPORTER switch expects a jmx_prometheus_javaagent jar in
# $KAFKA_HOME/libs, which quay.io/debezium/connect:3.7.1.Final does not ship (the JVM would not start). Instead
# kafka-connect opens remote JMX (JMXHOST/JMXPORT, supported by the image) on the internal network only, and this
# exporter reads it. The core stack does not depend on this image or its download.
FROM eclipse-temurin:25.0.5_8-jre-alpine

ARG JMX_EXPORTER_VERSION=1.7.0
# Created first: ADD --chmod would otherwise apply 444 to the directory it creates too (not traversable).
RUN mkdir -p /opt/jmx-exporter
ADD --chmod=444 \
    --checksum=sha256:f62f518d3a396320e4604d6981b6c4f4bd8a4fa46e6200791cf501cb3c6ddd44 \
    https://github.com/prometheus/jmx_exporter/releases/download/${JMX_EXPORTER_VERSION}/jmx_prometheus_standalone-${JMX_EXPORTER_VERSION}.jar \
    /opt/jmx-exporter/jmx_prometheus_standalone.jar

# The rules are bind-mounted (observability/kafka-connect-jmx/kafka-connect.yml) so they can change without a rebuild.
USER 65534:65534
EXPOSE 9404
ENTRYPOINT ["java", "-Xmx48m", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", \
            "-XX:MaxMetaspaceSize=48m", "-XX:ReservedCodeCacheSize=16m", \
            "-jar", "/opt/jmx-exporter/jmx_prometheus_standalone.jar", "9404", "/etc/jmx-exporter/config.yml"]
