FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Unprivileged runtime user. Nothing in the image is written to at runtime,
# so the app only needs to read its own jar.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
COPY --from=build --chown=app:app /app/target/*.jar app.jar
USER app

# Documentation only - the actual port comes from PORT (see
# application.properties: server.port=${PORT:8080}), which Render injects.
EXPOSE 8080

# Sized for a 512 MB container:
# - MaxRAMPercentage=70 leaves ~150 MB for metaspace, thread stacks, code
#   cache and the JVM itself, which live outside the heap.
# - SerialGC because G1's extra GC threads and bookkeeping are pure overhead
#   on a 0.1-CPU instance with a heap this small.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-jar", "app.jar"]
