#!/bin/sh

set -e  # Exit on error

# Default configuration
PUID=${PUID:-0}
PGID=${PGID:-0}

# Store PIDs in variables
JAVA_PID=""
NGINX_PID=""

cleanup() {
    echo "Cleaning up processes..."

    # Check and kill each process individually
    if [ -n "$JAVA_PID" ]; then
        kill -TERM "$JAVA_PID" 2>/dev/null || true
    fi
    if [ -n "$NGINX_PID" ]; then
        kill -TERM "$NGINX_PID" 2>/dev/null || true
    fi

    wait || true
    echo "All processes have been terminated"
    exit 0
}

setup_permissions() {
    if [ "$(id -u)" = "0" ] && [ "$PUID" != "0" ]; then
        echo "Setting up directory permissions..."
        chown -R "${PUID}:${PGID}" /app /etc/nginx /var/lib/nginx /var/log/nginx /run/nginx.pid /etc/nginx/nginx.conf
    fi
}

# Optional HTTP basic auth in front of the UI and API: set both AUTH_USERNAME and AUTH_PASSWORD.
setup_auth() {
    if [ -n "$AUTH_USERNAME" ] && [ -n "$AUTH_PASSWORD" ]; then
        printf '%s:%s\n' "$AUTH_USERNAME" "$(printf '%s' "$AUTH_PASSWORD" | mkpasswd -m sha512 -P 0)" > /etc/nginx/htpasswd
        export AUTH_BASIC='"Telegram Files"'
        echo "Basic auth enabled for user $AUTH_USERNAME"
    else
        : > /etc/nginx/htpasswd
        export AUTH_BASIC=off
    fi
}

start_services() {
    cmd_prefix=""
    if [ "$(id -u)" = "0" ] && [ "$PUID" != "0" ]; then
        cmd_prefix="su-exec ${PUID}:${PGID}"
    fi

    echo "Starting Java service..."
    if [ -n "$cmd_prefix" ]; then
        $cmd_prefix java $JAVA_OPTS --enable-native-access=ALL-UNNAMED -Djava.library.path=/app/tdlib -jar /app/api.jar &
    else
        java $JAVA_OPTS --enable-native-access=ALL-UNNAMED -Djava.library.path=/app/tdlib -jar /app/api.jar &
    fi
    JAVA_PID=$!

    echo "Starting Nginx service..."
    if [ -n "$cmd_prefix" ]; then
        $cmd_prefix nginx -g 'daemon off;' &
    else
        nginx -g 'daemon off;' &
    fi
    NGINX_PID=$!
}

# Set up signal handlers
trap cleanup TERM INT

setup_auth

# Replace nginx.conf.template with environment variables
envsubst '$NGINX_PORT $AUTH_BASIC' < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf

# Set up permissions
setup_permissions

# Start services
start_services

# Wait for all services to complete
wait
