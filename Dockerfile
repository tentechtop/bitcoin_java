# Build the JAR with the Linux CI verification job before building this image.
FROM eclipse-temurin:21-jre-noble@sha256:96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72
RUN groupadd --gid 10001 node && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin node \
    && mkdir -p /opt/node /var/lib/bitcoin-java /var/log/bitcoin-java \
    && chown -R 10001:10001 /opt/node /var/lib/bitcoin-java /var/log/bitcoin-java
WORKDIR /opt/node
COPY --chown=10001:10001 target/bitcoin_java-0.0.1-SNAPSHOT.jar /opt/node/app.jar
ENV SPRING_PROFILES_ACTIVE=prod NODE_HTTP_ADDRESS=0.0.0.0 NODE_HTTP_PORT=18333 \
    NODE_DATA_DIR=/var/lib/bitcoin-java LOGGING_FILE_PATH=/var/log/bitcoin-java \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
USER 10001:10001
VOLUME ["/var/lib/bitcoin-java", "/var/log/bitcoin-java"]
EXPOSE 18333/tcp 8333/udp
HEALTHCHECK --interval=30s --timeout=6s --start-period=60s --retries=3 \
    CMD ["java", "-Dloader.main=com.bit.coin.ops.NodeProbe", "-cp", "/opt/node/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
ENTRYPOINT ["java", "-jar", "/opt/node/app.jar"]
