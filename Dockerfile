# ----------------------------------------------------------------------------
# (C) Copyright IBM Corp. 2021
#
# SPDX-License-Identifier: Apache-2.0
# ----------------------------------------------------------------------------

# Keep in sync with keycloak.version in the root pom.xml
ARG KEYCLOAK_VERSION=26.7.4

# Build stage
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY keycloak-config ./keycloak-config
COPY keycloak-extensions ./keycloak-extensions

RUN mvn -B clean package -DskipTests


# Keycloak build stage: bakes the providers and build-time options into an optimized server
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder

ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true

COPY --from=build /build/keycloak-extensions/target/keycloak-extensions-*.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build


# Package stage
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Runtime settings (KC_DB_URL, KC_HOSTNAME, KC_PROXY_HEADERS, ...) come from the deployment's environment.
# Health and metrics are served on the management port (9000), which should not be published.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
