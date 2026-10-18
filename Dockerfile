FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -q package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/inventory-reliability-0.1.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
