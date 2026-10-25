FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app
RUN mkdir -p /app/data /app/private-files \
    && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /workspace/target/*.war /app/app.war

USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.war"]
