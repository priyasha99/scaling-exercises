# =============================================================
# Multi-stage build: keeps the final image small
# =============================================================
# Same structure as Exercise 04, now with JWT auth added.
# Connects to PostgreSQL + Redis. JWT verified in-process.
# =============================================================

# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies first (cached layer if pom.xml unchanged)
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# --- Stage 2: Run ---
FROM eclipse-temurin:17-jre-jammy

# Install curl for health checks
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Same JVM constraints as Exercise 01/02
# -Xmx256m : max heap 256MB
# -Xms128m : start with 128MB
# -XX:MaxMetaspaceSize=128m : limit metaspace
ENV JAVA_OPTS="-Xmx256m -Xms128m -XX:MaxMetaspaceSize=128m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
