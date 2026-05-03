# Unit tests run before packaging (integration-tagged tests stay excluded per pom.xml).
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:resolve-plugins dependency:resolve -DskipTests
COPY src ./src
RUN mvn -B test && mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /workspace/target/foundation-*.jar /app/app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
