# Build context is the repo root; this Dockerfile builds the orchestrator only.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY proto /workspace/proto
COPY services/orchestrator /workspace/services/orchestrator
WORKDIR /workspace/services/orchestrator
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre

# OpenTelemetry Java agent (zero-code instrumentation, attached via JAVA_TOOL_OPTIONS).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /otel \
    && curl -fsSL -o /otel/opentelemetry-javaagent.jar \
         https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v1.33.6/opentelemetry-javaagent.jar

# Non-root runtime user.
RUN useradd --system --uid 1001 --create-home appuser

WORKDIR /app
COPY --from=build /workspace/services/orchestrator/target/orchestrator-*.jar /app/app.jar
RUN chown -R appuser:appuser /app
# The H2 file database lives in /app/data. Create it here so that a named
# volume mounted on that path inherits the appuser ownership instead of being
# created root-owned (which would make the DB file unwritable for a non-root
# process and break datasource startup).
RUN mkdir -p /app/data && chown -R appuser:appuser /app/data
USER appuser

ENV JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar

EXPOSE 8080 50051
ENTRYPOINT ["java","-jar","/app/app.jar"]
