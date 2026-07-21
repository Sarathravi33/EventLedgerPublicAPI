# Build with context = repository root (see docker-compose.yml), so the parent POM and
# sibling module POM are visible — this is a multi-module Maven reactor build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# POMs only first, to let Docker cache the downloaded dependency layer across rebuilds that
# only change source files.
COPY pom.xml pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY event-gateway/pom.xml event-gateway/pom.xml
RUN mvn -q -pl account-service -am dependency:go-offline

COPY account-service/src account-service/src
RUN mvn -q -pl account-service -am package -Dmaven.test.skip=true

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# The "-exec" jar, not the plain one: account-service/pom.xml gives the executable Spring Boot
# jar that classifier specifically so the plain jar can stay the resolvable Maven artifact for
# event-gateway's test-scope dependency (see FIXES.md, Step 6).
COPY --from=build /workspace/account-service/target/account-service-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
