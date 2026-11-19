# Build the jar first: ./mvnw -DskipTests package
FROM eclipse-temurin:21-jre

RUN groupadd --system app && useradd --system --gid app --home-dir /app app

WORKDIR /app
COPY target/integration-service-*.jar /app/integration-service.jar
USER app

EXPOSE 8082
# Half of the container's memory for the heap, the rest for metaspace, threads and buffers.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/integration-service.jar"]
