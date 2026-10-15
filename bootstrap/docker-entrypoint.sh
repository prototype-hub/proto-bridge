#!/usr/bin/env bash
set -e

# WebFlux 的磁盘上传目录须在只读根文件系统启动前准备好。
mkdir -p "${UPLOAD_TEMP:-./data/proto-upload}"

exec java ${JAVA_OPTS:-} -server \
--add-opens \
java.base/java.lang=ALL-UNNAMED \
--add-opens \
java.base/java.util=ALL-UNNAMED \
--add-opens \
java.base/java.time=ALL-UNNAMED \
--add-opens \
java.base/java.util.concurrent=ALL-UNNAMED \
--add-opens \
java.base/java.io=ALL-UNNAMED \
--add-opens \
java.base/java.net=ALL-UNNAMED \
--add-opens \
java.base/java.text=ALL-UNNAMED \
--add-opens \
java.base/java.math=ALL-UNNAMED \
--add-opens \
java.scripting/javax.script=ALL-UNNAMED \
-XX:+EnableDynamicAgentLoading \
-Djava.security.egd=file:/dev/./urandom \
org.springframework.boot.loader.launch.PropertiesLauncher
