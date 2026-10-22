# Unit tests run before packaging (integration-tagged tests stay excluded per foundation/pom.xml).
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace
COPY pom.xml .
COPY plugin-api/pom.xml plugin-api/
COPY foundation/pom.xml foundation/
RUN mvn -B -pl foundation -am dependency:go-offline
COPY plugin-api/src plugin-api/src
COPY foundation/src foundation/src
RUN mvn -B test && mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /workspace/foundation/target/foundation-*.jar /app/app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
