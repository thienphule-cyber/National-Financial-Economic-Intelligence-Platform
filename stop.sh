#!/usr/bin/env bash
# stop.sh
# Stops the backend started by start.sh.
#
# Usage:
#   ./stop.sh            stop the platform
#   ./stop.sh --quiet    stop without printing messages

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$ROOT/.platform.pid"
PORT_FILE="$ROOT/.platform.port"
MAIN_CLASS="com.fintech.platform.PlatformApplication"
GRACE_SECONDS=20

QUIET=0
if [ "${1:-}" = "--quiet" ]; then
  QUIET=1
fi

say() {
  if [ "$QUIET" -eq 0 ]; then
    echo "$@"
  fi
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

# Prints all child, grandchild, ... PIDs of a process.
descendants() {
  local child
  for child in $(pgrep -P "$1" 2>/dev/null); do
    echo "$child"
    descendants "$child"
  done
}

# Prints the PIDs listening on a TCP port.
listener_pids() {
  command -v lsof >/dev/null 2>&1 || return 0
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null || true
}

TARGETS=""

add_target() {
  case " $TARGETS " in
    *" $1 "*) ;;
    *) TARGETS="$TARGETS $1" ;;
  esac
}

any_alive() {
  local pid
  for pid in $TARGETS; do
    if is_alive "$pid"; then
      return 0
    fi
  done
  return 1
}

# ---------------------------------------------------------------------
# Collect the processes to stop
# ---------------------------------------------------------------------

PID=""
PORT=""
[ -f "$PID_FILE" ] && PID="$(cat "$PID_FILE" 2>/dev/null || true)"
[ -f "$PORT_FILE" ] && PORT="$(cat "$PORT_FILE" 2>/dev/null || true)"

if [ -n "$PID" ] && is_alive "$PID" && is_ours "$PID"; then
  add_target "$PID"
  for child in $(descendants "$PID"); do
    add_target "$child"
  done
fi

# The Java process that listens on the port (only if it is this project's backend).
if [ -n "$PORT" ]; then
  for listener in $(listener_pids "$PORT"); do
    if ps -o command= -p "$listener" 2>/dev/null | grep -q "$MAIN_CLASS"; then
      add_target "$listener"
    fi
  done
fi

# No PID file (for example it was deleted): look for the backend by its main class.
if [ -z "$PID" ]; then
  for found in $(pgrep -f "$MAIN_CLASS" 2>/dev/null || true); do
    add_target "$found"
  done
fi

if [ -z "$TARGETS" ]; then
  say "The platform is not running."
  rm -f "$PID_FILE" "$PORT_FILE"
  exit 0
fi

# ---------------------------------------------------------------------
# Stop them
# ---------------------------------------------------------------------

say "Stopping the platform (PID:$TARGETS) ..."
# shellcheck disable=SC2086
kill $TARGETS 2>/dev/null || true

waited=0
while any_alive && [ "$waited" -lt "$GRACE_SECONDS" ]; do
  sleep 1
  waited=$((waited + 1))
done

if any_alive; then
  say "Some processes did not stop in time, forcing them ..."
  # shellcheck disable=SC2086
  kill -9 $TARGETS 2>/dev/null || true
  sleep 1
fi

rm -f "$PID_FILE" "$PORT_FILE"

if any_alive; then
  echo "Warning: some processes are still running:$TARGETS" >&2
  exit 1
fi

say "The platform has stopped."