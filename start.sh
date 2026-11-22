#!/usr/bin/env bash
# start.sh
# Starts the backend on the first free port and opens the dashboard in the browser.
#
# Usage:
#   ./start.sh               search for a free port starting at 8080
#   ./start.sh -p 9000       search for a free port starting at 9000
#   ./start.sh --no-open     do not open the browser
#   ./start.sh -h            show this help
#
# Files created in the project root:
#   logs/platform.log   backend output
#   .platform.pid       PID of the running backend (used by stop.sh)
#   .platform.port      port of the running backend

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT/backend"
LOG_DIR="$ROOT/logs"
LOG_FILE="$LOG_DIR/platform.log"
PID_FILE="$ROOT/.platform.pid"
PORT_FILE="$ROOT/.platform.port"

START_PORT=8080
MAX_TRIES=100
STARTUP_TIMEOUT=180
REQUIRED_JAVA=21
OPEN_BROWSER=1

# ---------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------

die() {
  echo "Error: $*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: ./start.sh [options]

  -p, --port PORT   first port to try (default 8080); the next free port is used if it is busy
      --no-open     do not open the browser
  -h, --help        show this help

Stop the platform with ./stop.sh
EOF
}

# True when the process exists and is not a zombie.
is_alive() {
  local stat
  stat="$(ps -o stat= -p "$1" 2>/dev/null | tr -d ' ')" || return 1
  [ -n "$stat" ] && [ "${stat#Z}" = "$stat" ]
}

# True when the PID looks like this project's backend (protects against reused PIDs).
is_ours() {
  ps -o command= -p "$1" 2>/dev/null | grep -Eq 'maven|spring-boot|com\.fintech\.platform'
}

port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null
  fi
}

find_free_port() {
  local port="$START_PORT"
  local last=$((START_PORT + MAX_TRIES - 1))
  while [ "$port" -le "$last" ]; do
    if ! port_in_use "$port"; then
      echo "$port"
      return 0
    fi
    port=$((port + 1))
  done
  return 1
}

open_url() {
  [ "$OPEN_BROWSER" -eq 1 ] || return 0
  if command -v open >/dev/null 2>&1; then
    open "$1" >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$1" >/dev/null 2>&1 || true
  elif command -v cmd.exe >/dev/null 2>&1; then
    cmd.exe /c start "" "$1" >/dev/null 2>&1 || true
  else
    echo "Open this link in your browser: $1"
  fi
}

# Prints the major Java version that Maven will use (0 when Java is missing).
java_major() {
  local bin="java"
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    bin="$JAVA_HOME/bin/java"
  fi

  local version major
  version="$("$bin" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')" || true
  major="${version%%[.-]*}"
  if [ "$major" = "1" ]; then
    major="$(echo "$version" | cut -d. -f2)"
  fi

  case "$major" in
    ''|*[!0-9]*) echo 0 ;;
    *) echo "$major" ;;
  esac
}

ensure_java() {
  local major candidate
  major="$(java_major)"
  if [ "$major" -ge "$REQUIRED_JAVA" ]; then
    return 0
  fi

  # Try common JDK locations (macOS and Homebrew).
  for candidate in \
    "$(/usr/libexec/java_home -v "${REQUIRED_JAVA}+" 2>/dev/null || true)" \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
      JAVA_HOME="$candidate"
      export JAVA_HOME
      PATH="$JAVA_HOME/bin:$PATH"
      export PATH
      major="$(java_major)"
      if [ "$major" -ge "$REQUIRED_JAVA" ]; then
        echo "Using JDK $major from $JAVA_HOME"
        return 0
      fi
    fi
  done

  die "JDK $REQUIRED_JAVA or newer is required.
Install it with:  brew install openjdk@21   then run ./start.sh again."
}

# ---------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    -p|--port)
      [ $# -ge 2 ] || die "Missing value for $1"
      START_PORT="$2"
      shift 2
      ;;
    --no-open)
      OPEN_BROWSER=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1 (use --help)"
      ;;
  esac
done

case "$START_PORT" in
  ''|*[!0-9]*) die "Port must be a number" ;;
esac
if [ "$START_PORT" -lt 1024 ] || [ "$START_PORT" -gt 65000 ]; then
  die "Port must be between 1024 and 65000"
fi

# ---------------------------------------------------------------------
# Already running?
# ---------------------------------------------------------------------

if [ -f "$PID_FILE" ]; then
  old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [ -n "$old_pid" ] && is_alive "$old_pid" && is_ours "$old_pid"; then
    old_port="$(cat "$PORT_FILE" 2>/dev/null || true)"
    echo "The platform is already running (PID $old_pid)."
    if [ -n "$old_port" ]; then
      echo "Dashboard: http://localhost:$old_port"
      open_url "http://localhost:$old_port"
    fi
    echo "Stop it with: ./stop.sh"
    exit 0
  fi
  rm -f "$PID_FILE" "$PORT_FILE"
fi

# ---------------------------------------------------------------------
# Requirements
# ---------------------------------------------------------------------

command -v curl >/dev/null 2>&1 || die "curl is required but was not found."
command -v mvn >/dev/null 2>&1 || die "Maven was not found. Install it with: brew install maven"
[ -s "$BACKEND_DIR/pom.xml" ] || die "backend/pom.xml is missing or empty."
ensure_java

if [ ! -s "$ROOT/frontend/index.html" ]; then
  echo "Warning: frontend/index.html is missing or empty, so the page will be blank." >&2
fi

# ---------------------------------------------------------------------
# Find a free port
# ---------------------------------------------------------------------

PORT="$(find_free_port)" || die "No free port found between $START_PORT and $((START_PORT + MAX_TRIES - 1))."
if [ "$PORT" != "$START_PORT" ]; then
  echo "Port $START_PORT is busy, using port $PORT instead."
fi

# ---------------------------------------------------------------------
# Start the backend
# ---------------------------------------------------------------------

on_interrupt() {
  echo
  echo "Interrupted, stopping the backend ..."
  bash "$ROOT/stop.sh" --quiet || true
  exit 130
}
trap on_interrupt INT TERM

mkdir -p "$LOG_DIR"
: > "$LOG_FILE"

echo "Starting the backend on port $PORT ..."
(
  # application.properties uses paths relative to backend/, so run from there.
  cd "$BACKEND_DIR"
  exec nohup mvn -B spring-boot:run "-Dspring-boot.run.arguments=--server.port=$PORT" \
    < /dev/null >> "$LOG_FILE" 2>&1
) &
PID=$!
echo "$PID" > "$PID_FILE"
echo "$PORT" > "$PORT_FILE"

# ---------------------------------------------------------------------
# Wait until the server answers
# ---------------------------------------------------------------------

echo "Waiting for the server (the first run can take a few minutes while Maven downloads dependencies) ..."
elapsed=0
while true; do
  if ! is_alive "$PID"; then
    echo
    echo "The backend stopped unexpectedly. Last lines of logs/platform.log:" >&2
    tail -n 30 "$LOG_FILE" >&2
    rm -f "$PID_FILE" "$PORT_FILE"
    exit 1
  fi

  if curl -fsS --max-time 2 "http://localhost:$PORT/api/health" 2>/dev/null | grep -q 'UP'; then
    break
  fi

  if [ "$elapsed" -ge "$STARTUP_TIMEOUT" ]; then
    echo
    echo "The server did not respond within $STARTUP_TIMEOUT seconds. Last lines of logs/platform.log:" >&2
    tail -n 30 "$LOG_FILE" >&2
    bash "$ROOT/stop.sh" --quiet || true
    exit 1
  fi

  printf '.'
  sleep 1
  elapsed=$((elapsed + 1))
done

trap - INT TERM
URL="http://localhost:$PORT"

echo
echo "The platform is running."
echo "  Dashboard : $URL"
echo "  Log file  : logs/platform.log"
echo "  Stop with : ./stop.sh"

open_url "$URL"