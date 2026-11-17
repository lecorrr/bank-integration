# The integration-service image. Build the jar first (./mvnw -DskipTests package from the repository root).
FROM eclipse-temurin:21-jre

# Never run as root inside the container.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app

WORKDIR /app
COPY target/integration-service-*.jar /app/integration-service.jar
USER app

EXPOSE 8082
# Half the container's memory limit for the heap; the rest is metaspace, threads and Camel's buffers.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/integration-service.jar"]
