FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /build

COPY pom.xml .
COPY src src

RUN mvn -B -ntp package -DskipTests \
    && test -s /build/target/sentinel-payment-fraud-engine-0.1.0-SNAPSHOT.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --gid 10001 sentinel \
    && useradd --uid 10001 --gid sentinel sentinel

COPY --from=build /build/target/sentinel-payment-fraud-engine-0.1.0-SNAPSHOT.jar app.jar

USER sentinel
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]