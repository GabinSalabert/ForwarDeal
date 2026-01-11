# ============================================
# Stage 1: Build with Java 21
# ============================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Install Node.js 20 for frontend build
RUN apt-get update && \
    apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy pom.xml first for dependency caching
COPY pom.xml .

# Install Maven
RUN apt-get update && apt-get install -y maven && apt-get clean

# Copy source code
COPY src ./src
COPY frontend ./frontend

# Build the application (includes frontend build via maven-frontend-plugin)
RUN mvn -DskipTests package

# ============================================
# Stage 2: Runtime with Java 21
# ============================================
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built JAR from build stage
COPY --from=build /app/target/forwardeal-0.0.1-SNAPSHOT.jar app.jar

# Expose port (Railway will set PORT env var)
EXPOSE 8080

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
