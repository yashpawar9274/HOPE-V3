FROM gradle:8.9-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/hope-cloud-agent-runtime /app
ENV PORT=8080
EXPOSE 8080
USER 10001
ENTRYPOINT ["/app/bin/hope-cloud-agent-runtime"]
