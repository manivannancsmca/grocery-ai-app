# syntax=docker/dockerfile:1.7
# Build context = repository root (needs libs/grocery-common and libs/grocery-event-schemas). From infrastructure/local:
#   docker compose build catalog-command-service
# No JDK or Maven needed on Windows: everything compiles inside the build stage.

FROM maven:3.9.16-eclipse-temurin-25-noble AS build
WORKDIR /workspace

# 1) Shared libraries (cached unless their sources change): the central event schemas, then grocery-common
COPY libs/grocery-event-schemas/pom.xml libs/grocery-event-schemas/pom.xml
COPY libs/grocery-event-schemas/src libs/grocery-event-schemas/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f libs/grocery-event-schemas/pom.xml -DskipTests install
COPY libs/grocery-common/pom.xml libs/grocery-common/pom.xml
COPY libs/grocery-common/src libs/grocery-common/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f libs/grocery-common/pom.xml -DskipTests install

# 2) Service dependencies (cached unless pom.xml changes), then sources
COPY services/catalog-command-service/pom.xml services/catalog-command-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f services/catalog-command-service/pom.xml dependency:go-offline -DexcludeGroupIds=com.grocery || true
COPY services/catalog-command-service/src services/catalog-command-service/src
# Tests run separately (they need Docker for Testcontainers): see README.md
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f services/catalog-command-service/pom.xml -DskipTests package \
 && cp services/catalog-command-service/target/catalog-command-service-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Runtime: small JRE image, non-root user, layered app (dependencies change less than code)
FROM eclipse-temurin:25.0.5_8-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8105
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
