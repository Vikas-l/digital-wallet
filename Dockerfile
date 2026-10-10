# syntax=docker/dockerfile:1
# One Dockerfile for both services:  docker build --build-arg SERVICE=wallet-service .

FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /workspace
COPY pom.xml .
COPY digital-wallet-common digital-wallet-common
COPY wallet-service wallet-service
COPY rewards-service rewards-service
# the cache mount keeps downloaded dependencies between builds, so rebuilds take seconds
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl ${SERVICE} -am package -DskipTests \
    && cp ${SERVICE}/target/${SERVICE}-*.jar /workspace/app.jar \
    && java -Djarmode=tools -jar /workspace/app.jar extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
# layered copy: dependencies change rarely, so only the small application layer is rebuilt
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
