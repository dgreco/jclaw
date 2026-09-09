# jclaw as a container, on the uber jar rather than the native image.
#
# The native binary is faster to start and smaller to ship, but it is built for one
# architecture and cross-building it inside Docker is slow enough to discourage anyone from
# trying the stack at all. A JRE image runs the same code, and `docker compose up` should be
# the cheapest possible way to see jclaw talk to a real PostgreSQL.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Poms first: dependency resolution is the slow half, and it only needs to rerun when a pom
# changes rather than on every source edit.
COPY pom.xml .
COPY jclaw-contracts/pom.xml jclaw-contracts/
COPY jclaw-domain/pom.xml jclaw-domain/
COPY jclaw-kernel/pom.xml jclaw-kernel/
COPY jclaw-loop/pom.xml jclaw-loop/
COPY jclaw-providers/pom.xml jclaw-providers/
COPY jclaw-tools/pom.xml jclaw-tools/
COPY jclaw-storage/pom.xml jclaw-storage/
COPY jclaw-app/pom.xml jclaw-app/
RUN mvn -B -q dependency:go-offline -DskipTests || true
COPY . .
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
# A non-root user: jclaw runs shell commands on behalf of a model, and the blast radius of that
# should not include the container's own filesystem.
RUN useradd --create-home --shell /bin/bash jclaw
WORKDIR /workspace
COPY --from=build /src/jclaw-app/target/jclaw-app-*.jar /opt/jclaw/jclaw.jar
# Both mount points must exist in the image and be owned by the runtime user: a named volume
# takes its ownership from the image directory the first time it is mounted, and a root-owned
# /state means the very first command fails trying to create its skills directory.
RUN mkdir -p /state /workspace && chown -R jclaw:jclaw /state /workspace
USER jclaw

ENV JCLAW_STATE_DIR=/state \
    JCLAW_WORKSPACE=/workspace
ENTRYPOINT ["java", "-jar", "/opt/jclaw/jclaw.jar"]
CMD ["--help"]
