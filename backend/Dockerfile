# syntax=docker.m.daocloud.io/docker/dockerfile:1.7

ARG BASE_REGISTRY=docker.m.daocloud.io

# Frontend compilation is architecture-independent. Run it on the native
# builder platform so multi-architecture builds do not execute npm under QEMU.
FROM --platform=$BUILDPLATFORM ${BASE_REGISTRY}/library/node:20-alpine AS web-build
WORKDIR /workspace
COPY h5/package.json h5/package-lock.json ./h5/
RUN cd h5 \
    && npm ci \
      --registry=https://registry.npmmirror.com \
      --no-audit \
      --no-fund \
      --fetch-timeout=120000 \
      --fetch-retries=5 \
      --fetch-retry-factor=2 \
      --fetch-retry-mintimeout=1000 \
      --fetch-retry-maxtimeout=30000
COPY h5 ./h5
RUN mkdir -p backend/src/main/resources/static/m \
    && cd h5 \
    && npm run build

# The JAR is also architecture-independent; keep Maven on the native builder.
FROM --platform=$BUILDPLATFORM ${BASE_REGISTRY}/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src ./src
COPY dist/tv-apk ./src/main/resources/static/tv-apk
COPY --from=web-build /workspace/backend/src/main/resources/static/m ./src/main/resources/static/m
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -DskipTests

FROM ${BASE_REGISTRY}/library/eclipse-temurin:21-jre-alpine
ARG KTV_RELEASE_VERSION=0.1.0-dev
ARG KTV_RELEASE_VERSION_CODE=1
WORKDIR /app

ENV KTV_RELEASE_VERSION=${KTV_RELEASE_VERSION} \
    KTV_RELEASE_VERSION_CODE=${KTV_RELEASE_VERSION_CODE} \
    KTV_RELEASE_ARMEABI_V7A_APK=classpath:/static/tv-apk/home-ktv-tv-${KTV_RELEASE_VERSION}-armeabi-v7a.apk \
    KTV_RELEASE_ARM64_V8A_APK=classpath:/static/tv-apk/home-ktv-tv-${KTV_RELEASE_VERSION}-arm64-v8a.apk

# 打包 FFmpeg（含 ffprobe），供曲库扫描解析媒体和硬件转码。
# Alpine v3.22 community 仓库的 ffmpeg 6.x 默认已启用 --enable-vaapi，因此 h264_vaapi/hevc_vaapi
# 编码器开箱即有；不再引入 edge 仓库（含未对齐的 libplacebo/libglslang 子包）。
# Intel iHD 驱动 + libva-utils / mesa-va-gallium 是检测/运行时必需的；它们只在 x86_64 安装。
RUN sed -i 's#https://dl-cdn.alpinelinux.org#https://mirrors.aliyun.com#g' /etc/apk/repositories \
    && for attempt in 1 2 3; do \
         apk add --no-cache ffmpeg libva-utils && break; \
         if [ "$attempt" -eq 3 ]; then exit 1; fi; \
         sleep 5; \
       done \
    && if [ "$(apk --print-arch)" = "x86_64" ]; then \
         apk add --no-cache intel-media-driver mesa-va-gallium; \
       elif [ "$(apk --print-arch)" = "aarch64" ]; then \
         apk add --no-cache rockchip-mpp-dev 2>/dev/null || true; \
       fi \
    && ffmpeg -hide_banner -encoders 2>/dev/null | grep -E "h264_vaapi|hevc_vaapi" \
       || (echo "FATAL: installed ffmpeg does not expose h264_vaapi/hevc_vaapi encoders" && exit 1)

COPY --from=build /build/target/home-ktv-server.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
