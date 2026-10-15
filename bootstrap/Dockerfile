FROM eclipse-temurin:21-jdk-noble AS builder
WORKDIR /application
ARG JAR_FILE=target/bootstrap-1.0.0.jar
COPY ${JAR_FILE} app.jar
# Created only after release verify validates the actual JAR and its legal material.
COPY target/bootstrap-1.0.0.jar.license-sha256 app.jar.license-sha256
RUN sha256sum -c app.jar.license-sha256 \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher \
    && mkdir legal \
    && cd legal \
    && jar -xf ../app.jar META-INF/LICENSE META-INF/NOTICE META-INF/third-party \
    && test -s META-INF/LICENSE \
    && test -s META-INF/NOTICE \
    && grep -q '"collectionComplete": true' META-INF/third-party/inventory.json
FROM eclipse-temurin:21-jdk-noble

WORKDIR /application
COPY --from=builder application/app/dependencies/ ./
COPY --from=builder application/app/snapshot-dependencies/ ./
COPY --from=builder application/app/spring-boot-loader/ ./
COPY --from=builder application/app/application/ ./
# Human-readable release material remains accessible outside the application archive.
COPY --from=builder application/legal/META-INF/ /application/legal/
LABEL org.opencontainers.image.licenses="Apache-2.0"
COPY docker-entrypoint.sh ./
RUN chmod +x docker-entrypoint.sh
ENTRYPOINT ["./docker-entrypoint.sh"]
