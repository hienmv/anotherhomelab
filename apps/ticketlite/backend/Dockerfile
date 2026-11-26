# syntax=docker/dockerfile:1
# Build stage runs on the CI/host architecture (JVM bytecode is portable),
# runtime stage is the target architecture (linux/arm64 for the Pis). No emulation needed.
# Same Gradle + JDK as the wrapper (gradle/wrapper/gradle-wrapper.properties) and the IDE
# (gradle/gradle-daemon-jvm.properties): change all three together.
FROM --platform=$BUILDPLATFORM gradle:8.14.6-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
# Cached layer: downloads all jars incl. the Kotlin compiler; reruns only if the build files change.
RUN gradle --no-daemon -q resolveDependencies
COPY src ./src
# --offline: everything must come from the cached layer above (no surprise downloads in CI).
RUN gradle --no-daemon --offline test installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/ticketlite /app
# OpenTelemetry Java agent: traces every HTTP request, SQL query and Redis call, and adds trace_id /
# span_id to every log line (docs/learn/10-tracing.md). Pinned and checksum-verified.
# Settings come from OTEL_* env vars (k8s/apps/ticketlite/backend.yaml); OTEL_JAVAAGENT_ENABLED=false turns it off.
ADD --checksum=sha256:e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149 \
    https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.33.1/opentelemetry-javaagent-2.33.1.jar \
    /app/otel/opentelemetry-javaagent.jar
RUN chmod 644 /app/otel/opentelemetry-javaagent.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -javaagent:/app/otel/opentelemetry-javaagent.jar"
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/ticketlite"]
