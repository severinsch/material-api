# Stage 1: Cache Gradle dependencies
FROM gradle:8.14-alpine AS cache
RUN mkdir -p /home/gradle/cache_home
ENV GRADLE_USER_HOME=/home/gradle/cache_home
COPY build.gradle.* gradle.properties /home/gradle/app/
WORKDIR /home/gradle/app
RUN gradle clean build -i --stacktrace

# Stage 2: Build Application
FROM gradle:8.14-alpine AS builder
COPY --from=cache /home/gradle/cache_home /home/gradle/.gradle
COPY . /usr/src/app/
WORKDIR /usr/src/app
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# Generate OpenAPI documentation
RUN gradle buildOpenApi --no-daemon

# Build the fat JAR, Gradle also supports shadow
RUN gradle buildFatJar --no-daemon

# --- Stage 3: The Final Image ---
# This stage uses a minimal JRE to run the application, resulting in a much smaller image.
FROM eclipse-temurin:24-alpine

WORKDIR /app

ENV RESOURCES_BASE_PATH="/app/resources"

# Copy the built JAR from the 'builder' stage into the final image.
RUN mkdir -p $RESOURCES_BASE_PATH
COPY --from=builder /home/gradle/src/build/libs/*.jar  /app/application.jar
COPY --from=builder /home/gradle/src/src/main/resources/ $RESOURCES_BASE_PATH

# Expose the port that the Ktor application will listen on.
EXPOSE 8080

# The command to run when the container starts.
ENTRYPOINT ["java", "-jar", "/app/application.jar"]