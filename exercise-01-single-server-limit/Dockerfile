# =============================================================
# Multi-stage build: keeps the final image small
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
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Constrained JVM settings - this is the whole point!
# -Xmx256m : max heap 256MB (a realistic small server)
# -Xms128m : start with 128MB
# -XX:MaxMetaspaceSize=128m : limit metaspace too
# These constraints make the breaking point reachable
ENV JAVA_OPTS="-Xmx256m -Xms128m -XX:MaxMetaspaceSize=128m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
