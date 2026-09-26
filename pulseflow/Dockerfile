FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
RUN mvn package -DskipTests --batch-mode --no-transfer-progress
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --gid 10001 pulseflow && useradd --uid 10001 --gid pulseflow --create-home pulseflow \
    && mkdir -p /data/applogs/xxl-job && chown -R pulseflow:pulseflow /data
WORKDIR /app
COPY --from=build /build/pulseflow-boot/target/pulseflow-boot-1.0.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
