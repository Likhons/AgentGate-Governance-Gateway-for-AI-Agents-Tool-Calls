# Stage 1: Build with Gradle Wrapper & JDK 21
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

# Stage 2: Minimal JRE 21 Runtime tuned for <8GB RAM constraint (C1)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S agentgate && adduser -S agentgate -G agentgate
USER agentgate
COPY --from=builder /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
