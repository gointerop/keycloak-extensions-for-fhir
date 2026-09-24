# ----------------------------------------------------------------------------
# (C) Copyright IBM Corp. 2021
#
# SPDX-License-Identifier: Apache-2.0
# ----------------------------------------------------------------------------

# Note: this Dockerfile must be executed from the root of the project
# Example:
#   > docker build . -f keycloak-config/Dockerfile

# Build stage
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY keycloak-config ./keycloak-config

RUN mvn -B clean package -f keycloak-config -DskipTests


# Package stage
FROM eclipse-temurin:21-jre

RUN groupadd -r keycloak-client -g 1001 && \
    useradd -u 1001 -r -g 1001 -m -d /home/keycloak-client -s /sbin/nologin keycloak-client

COPY --from=build --chown=1001:0 /build/keycloak-config/target/keycloak-config-*.jar /jars/
COPY --from=build --chown=1001:0 /build/keycloak-config/target/dependency/* /jars/
COPY --from=build --chown=1001:0 /build/keycloak-config/src/main/resources/config/keycloak-config.json /config/
COPY --from=build --chown=1001:0 /build/keycloak-config/run.sh /

# Keycloak 17+ serves from the root path; append /auth only for servers started with --http-relative-path=/auth
ENV KEYCLOAK_BASE_URL=http://host.docker.internal:8080 \
    KEYCLOAK_REALM=test \
    KEYCLOAK_USER=admin \
    KEYCLOAK_PASSWORD=admin \
    FHIR_BASE_URL=https://localhost:9443/fhir-server/api/v4

USER 1001

ENTRYPOINT ["/run.sh"]

CMD ["-configFile", "config/keycloak-config.json"]
