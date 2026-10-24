# The Kilnmark server image: one image, roles chosen with KILNMARK_ROLES.
# Build the app first: ./gradlew :server:quarkusBuild  (output in dist/build/server/quarkus-app)
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 1001 kilnmark
WORKDIR /opt/kilnmark
COPY --chown=kilnmark dist/build/server/quarkus-app/ ./

USER kilnmark
EXPOSE 8080
ENV KILNMARK_ROLES=api,scheduler,worker
ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "quarkus-run.jar"]
