# --- Build stage -----------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# --- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:17-jre
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
