# `verify` runs the unit tests, then packages (integration-tagged tests stay excluded per foundation/pom.xml).
# foundation test-depends on the hotel and dental plugin ZIPs, which only exist once those have been packaged, so a
# bare `mvn test` cannot resolve it.
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace
COPY pom.xml .
COPY plugin-api/pom.xml plugin-api/
COPY libs/cccd-scan/pom.xml libs/cccd-scan/
COPY plugins/hotel/pom.xml plugins/hotel/
COPY plugins/dental/pom.xml plugins/dental/
COPY foundation/pom.xml foundation/
RUN mvn -B -pl foundation -am dependency:go-offline
COPY plugin-api/src plugin-api/src
COPY libs/cccd-scan/src libs/cccd-scan/src
COPY plugins/hotel/src plugins/hotel/src
COPY plugins/dental/src plugins/dental/src
COPY foundation/src foundation/src
RUN mvn -B verify

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /workspace/foundation/target/foundation-*.jar /app/app.jar
# The server image ships the hotel and dental plugins, so a normal deploy installs them with no manual step.
COPY --from=builder /workspace/plugins/hotel/target/hotel-*-plugin.zip /app/plugins/
COPY --from=builder /workspace/plugins/dental/target/dental-*-plugin.zip /app/plugins/
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
