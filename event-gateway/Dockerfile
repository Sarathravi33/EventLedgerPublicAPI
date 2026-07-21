# Build with context = repository root (see docker-compose.yml). event-gateway declares a
# test-scope dependency on account-service (see event-gateway/pom.xml comment / FIXES.md,
# Step 6), so this build copies both modules' sources and lets "-am" build account-service
# first within this same reactor invocation — self-contained, no reliance on the other
# Dockerfile having built (or published) account-service first.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY event-gateway/pom.xml event-gateway/pom.xml
RUN mvn -q -pl event-gateway -am dependency:go-offline

COPY account-service/src account-service/src
COPY event-gateway/src event-gateway/src
RUN mvn -q -pl event-gateway -am package -Dmaven.test.skip=true

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/event-gateway/target/event-gateway.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
