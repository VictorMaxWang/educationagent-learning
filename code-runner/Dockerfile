# 固定语言运行时版本；编译器/调试器的实际包版本写入镜像版本清单。
FROM node:24.10.0-bookworm-slim AS node
FROM eclipse-temurin:21.0.8_9-jdk-jammy AS java
FROM python:3.13.7-slim-bookworm
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update && apt-get install -y --no-install-recommends g++=4:12.2.0-3 gdb=13.1-3 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/runner
COPY runner.py ./runner.py
COPY drivers ./drivers
RUN javac --add-modules jdk.jdi -encoding UTF-8 drivers/java/TraceMain.java \
    && python --version > /opt/runner/versions.txt 2>&1 \
    && node --version >> /opt/runner/versions.txt \
    && java -version >> /opt/runner/versions.txt 2>&1 \
    && g++ --version >> /opt/runner/versions.txt \
    && gdb --version >> /opt/runner/versions.txt \
    && chmod -R a-w /opt/runner
USER 65532:65532
WORKDIR /job
ENTRYPOINT ["python", "-I", "/opt/runner/runner.py"]
