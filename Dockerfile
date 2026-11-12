# Two stage image for latifi-api.
#
# The build stage never starts a database and no Maven phase it runs connects to one: a Dokploy build
# has no route to the database network. Tests and formatting checks stay in the ci workflow.
# No ENV in this file carries a credential. Runtime secrets arrive from the environment.

# eclipse-temurin 21 JDK, noble variant, pinned by the digest of its image index.
FROM eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9 AS build
WORKDIR /workspace

# The Maven wrapper checks the sha256 of the distribution zip. Without unzip it falls back to the
# tar.gz of the same release, whose checksum differs, and the build stops.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

# Dependencies first, so they stay in their own layer until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package \
    && set -- target/latifi-api-*.jar \
    && jars=0 \
    && for f in "$@"; do case "$f" in *.original) ;; *) jars=$((jars + 1)); jar="$f" ;; esac; done \
    && test "$jars" -eq 1 \
    && cp "$jar" /workspace/app.jar

# eclipse-temurin 21 JRE, noble variant, pinned by the digest of its image index.
# The base image already ships curl, which the HEALTHCHECK needs.
FROM eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515

ARG GIT_SHA=unknown

LABEL org.opencontainers.image.source="https://github.com/Arquitectura-de-Softwares-Emergentes/latifi-api" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.title="latifi-api"

RUN groupadd --system --gid 10001 latifi \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin latifi

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

USER 10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
