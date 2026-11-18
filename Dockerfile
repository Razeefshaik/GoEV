# Backend image for Render (Render has no native Java runtime, so it builds this Dockerfile).
# Builds both Spring Boot jars; GOEV_APP picks which one runs:
#   GOEV_APP=full      (default) full GoEV server: needs PostgreSQL, Redis and an MQTT broker
#   GOEV_APP=research  standalone V2G research API (/api/v2g, /v2g): no external services

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon --version
COPY src ./src
RUN ./gradlew --no-daemon -x test bootJar researchJar \
 && cp build/libs/goev-server-*-SNAPSHOT.jar /app/goev-full.jar \
 && cp build/libs/goev-server-*-research.jar /app/goev-research.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 goev && mkdir -p /app/var && chown goev /app/var
COPY --from=build /app/goev-full.jar /app/goev-research.jar ./
COPY docker/render/entrypoint.sh ./entrypoint.sh
RUN sed -i 's/\r$//' entrypoint.sh && chmod +x entrypoint.sh
USER goev
ENV GOEV_APP=full \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["./entrypoint.sh"]
