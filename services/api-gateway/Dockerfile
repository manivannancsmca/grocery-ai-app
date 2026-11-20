# syntax=docker/dockerfile:1.7
# Build context = repository root (same convention as every service). From infrastructure/local:
#   docker compose build api-gateway

FROM maven:3.9.16-eclipse-temurin-25-noble AS build
WORKDIR /workspace
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f services/api-gateway/pom.xml dependency:go-offline || true
COPY services/api-gateway/src services/api-gateway/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f services/api-gateway/pom.xml -DskipTests package \
 && cp services/api-gateway/target/api-gateway-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
