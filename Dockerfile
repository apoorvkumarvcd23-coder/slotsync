FROM node:24.14.0-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
RUN ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
COPY --from=web /web/dist/ src/main/resources/static/
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S slotsync && adduser -S slotsync -G slotsync
COPY --from=build /build/target/slotsync-0.1.0-SNAPSHOT.jar app.jar
USER slotsync
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
