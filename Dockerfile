# ============================================================
# Build stage
# ============================================================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Maven wrapper and project descriptor first
# so dependency resolution can be cached by Docker.
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./

RUN chmod +x mvnw

# Resolve dependencies before copying source.
RUN ./mvnw -B dependency:go-offline

# Copy application source.
COPY src ./src

# Build the executable Spring Boot JAR.
RUN ./mvnw -B -DskipTests package


# ============================================================
# Runtime stage
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Run as a non-root user.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser

COPY --from=build /app/target/aiassistant-0.0.1-SNAPSHOT.jar /app/app.jar

RUN chown appuser:appuser /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]