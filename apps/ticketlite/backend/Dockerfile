# syntax=docker/dockerfile:1
# Build stage runs on the CI/host architecture (JVM bytecode is portable),
# runtime stage is the target architecture (linux/arm64 for the Pis). No emulation needed.
FROM --platform=$BUILDPLATFORM gradle:8.14-jdk21 AS build
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
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/ticketlite"]
