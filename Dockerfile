# Build stage: the dependency layer is cached as long as the build files don't change.
FROM gradle:9.8.1-jdk21-alpine AS builder
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN gradle dependencies --no-daemon -q > /dev/null
COPY src ./src
RUN gradle buildFatJar --no-daemon

# Runtime stage: only the JRE and the application jar.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar /app/application.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
