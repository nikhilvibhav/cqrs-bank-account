# syntax=docker/dockerfile:1

# Builds a layered image of the application, with the same layers as Spring Boot's `bootBuildImage`:
# dependencies, the Spring Boot loader, snapshot dependencies and the application itself, each in its own
# image layer. A code change then only rebuilds and ships the small application layer.

# 1. Build the executable jar. The JDK matches the Gradle toolchain (Java 25), so Gradle uses it directly.
#    The Kotlin compiler runs inside Gradle's JVM rather than in a daemon of its own, so the build fits in
#    the 2 GB a Docker or Podman VM often has.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -Dorg.gradle.jvmargs=-Xmx1g -Pkotlin.compiler.execution.strategy=in-process \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} application.jar \;

# 2. Split the jar into its layers with Spring Boot's jar tools.
FROM eclipse-temurin:25-jre AS extract
WORKDIR /extract
COPY --from=build /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

# 3. The runtime image: one COPY per layer, least to most often changed.
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home spring
WORKDIR /application
COPY --from=extract /extract/extracted/dependencies/ ./
COPY --from=extract /extract/extracted/spring-boot-loader/ ./
COPY --from=extract /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract /extract/extracted/application/ ./
USER spring
EXPOSE 8080
# Size the heap from the container's memory limit rather than the JVM's default of a quarter of it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
