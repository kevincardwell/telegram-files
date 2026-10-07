FROM gradle:9.8-jdk25-alpine AS api-builder

WORKDIR /app

COPY ./api/build.gradle ./api/settings.gradle ./
COPY ./api/gradle ./gradle
RUN gradle dependencies --no-daemon -q

COPY ./api .
RUN gradle shadowJar --no-daemon && \
    cp /app/build/libs/*.jar /app/api.jar && \
    jdeps --multi-release 25 --print-module-deps --ignore-missing-deps /app/api.jar > /app/dependencies.txt

FROM eclipse-temurin:25-jdk-alpine AS runtime-builder

WORKDIR /custom-jre

COPY --from=api-builder /app/dependencies.txt .
RUN apk add --no-cache binutils && \
    jlink \
        --add-modules $(cat dependencies.txt) \
        --output jre \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-6

FROM node:24-alpine AS web-builder

WORKDIR /web

ENV NEXT_PUBLIC_API_URL=/api \
    NEXT_PUBLIC_WS_URL=/ws \
    NEXT_TELEMETRY_DISABLED=1 \
    SKIP_ENV_VALIDATION=1

COPY ./web/package*.json ./
RUN npm ci

COPY ./web .
RUN npm run build

FROM alpine:3.24 AS final

WORKDIR /app

ARG TARGETARCH
ENV JAVA_HOME=/jre \
    PATH="/jre/bin:$PATH" \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    NGINX_PORT=80 \
    APP_ROOT=/app/data

# The TDLib JNI library is built against musl: it needs libstdc++, OpenSSL 3 and zlib, no glibc shim.
RUN addgroup -S tf && \
    adduser -S -G tf tf && \
    apk add --no-cache nginx curl tini su-exec gettext libstdc++ libssl3 zlib && \
    touch /run/nginx.pid /etc/nginx/htpasswd && \
    chown -R tf:tf /app /etc/nginx /var/lib/nginx /var/log/nginx /run/nginx.pid && \
    printf '#!/bin/sh\nexec java --enable-native-access=ALL-UNNAMED -Djava.library.path=/app/tdlib -cp /app/api.jar telegram.files.Maintain "$@"\n' > /usr/bin/tfm && \
    chmod +x /usr/bin/tfm

COPY --from=runtime-builder --chown=tf:tf /custom-jre/jre /jre
COPY --from=api-builder --chown=tf:tf /app/api.jar /app/api.jar
COPY --from=web-builder --chown=tf:tf /web/out /app/web/

COPY --chown=tf:tf ./tdlib/linux_$TARGETARCH /app/tdlib
COPY --chown=tf:tf ./entrypoint.sh .
COPY --chown=tf:tf ./nginx.conf.template /etc/nginx/nginx.conf.template

EXPOSE $NGINX_PORT

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["/bin/sh", "./entrypoint.sh"]
