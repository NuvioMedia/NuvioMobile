#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  Lentra Debug Log Viewer
# ─────────────────────────────────────────────────────────────────────────────
#  Streams live ADB logcat for the Lentra debug build, colour-coded by log
#  level.  Press  C  to clear all logs on screen.  Press  Q  to quit.
#
#  Usage:
#    ./scripts/debug_logs.sh [options]
#
#  Options:
#    -s, --serial <id>     ADB device serial (optional, for multi-device)
#    -p, --package <name>  Android package/applicationId (default: debug build)
#    -t, --tag <regex>     Additional logcat tag filter regex (default: all app)
#        --p2p             Focus on Nuvio Engine startup/playback diagnostics
#    -c, --clear           Clear logcat buffer before streaming
#    -h, --help            Show help
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

PACKAGE="${NUVIO_LOG_PACKAGE:-com.lentradebug.app}"
SERIAL=""
CLEAR_BUFFER=false
TAG_FILTER=""
P2P_MODE=false
STREAM_PID=""
USER_QUIT=false

# ── Log file ────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="${PROJECT_DIR}/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="${LOG_DIR}/debug_$(date +%Y%m%d_%H%M%S).log"

# ── Noise suppression ───────────────────────────────────────────────────────
NOISE_TAGS='EGL_emulation|OpenGLRenderer|eglCodecCommon|goldfish|gralloc|hwcomposer|SurfaceFlinger|chatty|ConfigStore|libEGL|MediaCodec|AudioTrack|AudioFlinger|BufferQueueProducer|GraphicBufferSource|OMXClient'
P2P_TAGS='NuvioP2PDiag|NuvioPlayerDiag|P2pStreamingEngine|NuvioPlayer'

# ── ANSI colour codes ───────────────────────────────────────────────────────
RST='\033[0m'
BOLD='\033[1m'
DIM='\033[2m'

CLR_V='\033[36m'
CLR_D='\033[34m'
CLR_I='\033[32m'
CLR_W='\033[33m'
CLR_E='\033[31m'
CLR_F='\033[35m'

CLR_HEADER='\033[38;5;141m'
CLR_ACCENT='\033[38;5;75m'
CLR_META='\033[38;5;245m'
CLR_HOTKEY='\033[38;5;220m'

# ── Helpers ──────────────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage: ./scripts/debug_logs.sh [options]

Stream live colour-coded ADB logs for Lentra debug builds.

Options:
  -s, --serial <id>     ADB device serial (optional)
  -p, --package <name>  Android package/applicationId (default: com.lentradebug.app)
  -t, --tag <regex>     Additional grep regex to filter log tags
      --p2p             Focus on engine phases, route telemetry, and player startup
  -c, --clear           Clear logcat buffer before streaming
  -h, --help            Show this help

Hotkeys (while running):
  C   Clear all logs currently shown in the terminal
  Q   Quit the log viewer

Examples:
  ./scripts/debug_logs.sh
  ./scripts/debug_logs.sh --serial emulator-5554
  ./scripts/debug_logs.sh --package com.lentra.app
  ./scripts/debug_logs.sh --clear --p2p
  ./scripts/debug_logs.sh --serial emulator-5554 --clear --p2p
  ./scripts/debug_logs.sh --tag 'MetaDetailsRepo|SeriesContent'
  ./scripts/debug_logs.sh --clear --tag 'Sync|Auth'
EOF
}

# ── Parse arguments ──────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)  SERIAL="${2:-}"; shift 2 ;;
    -p|--package) PACKAGE="${2:-}"; shift 2 ;;
    -t|--tag)     TAG_FILTER="${2:-}"; shift 2 ;;
    --p2p)        P2P_MODE=true; shift ;;
    -c|--clear)   CLEAR_BUFFER=true; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if $P2P_MODE; then
  if [[ -n "$TAG_FILTER" ]]; then
    TAG_FILTER="${P2P_TAGS}|${TAG_FILTER}"
  else
    TAG_FILTER="$P2P_TAGS"
  fi
fi

# ── ADB setup ────────────────────────────────────────────────────────────────
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=( -s "$SERIAL" )
fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo -e "${CLR_E}✗ No ADB device detected. Connect a device or start an emulator first.${RST}" >&2
  "${ADB[@]}" devices 2>/dev/null || true
  exit 1
fi

DEVICE_MODEL=$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "unknown")
ANDROID_VER=$("${ADB[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "?")
ANDROID_SDK=$("${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || echo "?")
DEVICE_ABI=$("${ADB[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || echo "unknown")
DEVICE_SERIAL=$("${ADB[@]}" get-serialno 2>/dev/null | tr -d '\r' || echo "unknown")

if $CLEAR_BUFFER; then
  "${ADB[@]}" logcat -c
fi

# ── Resolve app UID ──────────────────────────────────────────────────────────
uid_from_pm() {
  "${ADB[@]}" shell cmd package list packages -U "$PACKAGE" 2>/dev/null \
    | tr -d '\r' \
    | sed -nE 's/.*uid:([0-9]+).*/\1/p' \
    | head -n1
}

uid_from_dumpsys() {
  "${ADB[@]}" shell dumpsys package "$PACKAGE" 2>/dev/null \
    | tr -d '\r' \
    | sed -nE 's/.*userId=([0-9]+).*/\1/p' \
    | head -n1
}

APP_UID="$(uid_from_pm || true)"
if [[ -z "$APP_UID" ]]; then
  APP_UID="$(uid_from_dumpsys || true)"
fi

APP_VERSION=$("${ADB[@]}" shell dumpsys package "$PACKAGE" 2>/dev/null \
  | tr -d '\r' \
  | sed -nE 's/.*versionName=([^[:space:]]+).*/\1/p' \
  | head -n1)
APP_VERSION="${APP_VERSION:-unknown}"

write_session_metadata() {
  {
    echo "# Lentra Android diagnostic capture"
    echo "# started=$(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "# serial=${DEVICE_SERIAL} model=${DEVICE_MODEL} android=${ANDROID_VER} sdk=${ANDROID_SDK} abi=${DEVICE_ABI}"
    echo "# package=${PACKAGE} version=${APP_VERSION} uid=${APP_UID:-unknown}"
    echo "# p2pMode=${P2P_MODE} filter=${TAG_FILTER:-all-app-logs}"
  } >> "$LOG_FILE"
}

# ── Colour-coding function ──────────────────────────────────────────────────
colorize_line() {
  local line="$1"
  local level=""
  if [[ "$line" =~ ^[[:space:]]*[0-9]{2}-[0-9]{2}[[:space:]]+[0-9:.]+[[:space:]]+([VDIWEF])/ ]]; then
    level="${BASH_REMATCH[1]}"
  elif [[ "$line" =~ ^([VDIWEF])/ ]]; then
    level="${BASH_REMATCH[1]}"
  else
    level="${line:0:1}"
  fi
  local clr=""
  case "$level" in
    V) clr="$CLR_V" ;;
    D) clr="$CLR_D" ;;
    I) clr="$CLR_I" ;;
    W) clr="$CLR_W" ;;
    E) clr="$CLR_E" ;;
    F) clr="$CLR_F" ;;
    *) clr="$DIM"   ;;
  esac

  local badge=""
  case "$level" in
    V) badge="${CLR_V}${BOLD} VRB ${RST}" ;;
    D) badge="${CLR_D}${BOLD} DBG ${RST}" ;;
    I) badge="${CLR_I}${BOLD} INF ${RST}" ;;
    W) badge="${CLR_W}${BOLD} WRN ${RST}" ;;
    E) badge="${CLR_E}${BOLD} ERR ${RST}" ;;
    F) badge="${CLR_F}${BOLD} FTL ${RST}" ;;
    *) badge="${DIM} ??? ${RST}" ;;
  esac

  echo -e "${badge} ${clr}${line}${RST}"
}

# ── Stream logcat with colour ───────────────────────────────────────────────
stream_logcat_colored() {
  local mode="$1"
  local value="$2"
  local noise_re="$NOISE_TAGS"

  "${ADB[@]}" logcat -v time "$mode" "$value" 2>/dev/null \
    | while IFS= read -r raw_line; do
        if [[ "$raw_line" =~ $noise_re ]]; then
          continue
        fi
        if [[ -n "$TAG_FILTER" ]]; then
          if [[ ! "$raw_line" =~ $TAG_FILTER ]]; then
            continue
          fi
        fi
        echo "$raw_line" >> "$LOG_FILE"
        colorize_line "$raw_line"
      done
}

# ── Process management ───────────────────────────────────────────────────────
stop_stream() {
  if [[ -n "$STREAM_PID" ]] && kill -0 "$STREAM_PID" 2>/dev/null; then
    kill -- -"$STREAM_PID" 2>/dev/null || kill "$STREAM_PID" 2>/dev/null || true
    wait "$STREAM_PID" 2>/dev/null || true
  fi
  STREAM_PID=""
}

start_stream() {
  local mode="$1"
  local value="$2"
  set -m
  stream_logcat_colored "$mode" "$value" &
  STREAM_PID=$!
  set +m
}

clear_terminal() {
  if [[ -t 1 ]]; then
    printf '\033[2J\033[3J\033[H'
    print_banner
  fi
}

# ── Pretty banner ───────────────────────────────────────────────────────────
print_banner() {
  echo -e ""
  echo -e "${CLR_HEADER}${BOLD}  ╔══════════════════════════════════════════════════════╗${RST}"
  echo -e "${CLR_HEADER}${BOLD}  ║           📺  Lentra Debug Log Viewer                ║${RST}"
  echo -e "${CLR_HEADER}${BOLD}  ╚══════════════════════════════════════════════════════╝${RST}"
  echo -e ""
  echo -e "  ${CLR_META}Device:${RST}  ${CLR_ACCENT}${DEVICE_MODEL}${RST} ${CLR_META}(Android ${ANDROID_VER})${RST}"
  echo -e "  ${CLR_META}ABI / SDK:${RST} ${CLR_ACCENT}${DEVICE_ABI} / ${ANDROID_SDK}${RST}"
  echo -e "  ${CLR_META}Package:${RST} ${CLR_ACCENT}${PACKAGE}${RST}"
  echo -e "  ${CLR_META}App version:${RST} ${CLR_ACCENT}${APP_VERSION}${RST}"
  echo -e "  ${CLR_META}Log file:${RST} ${CLR_ACCENT}${LOG_FILE}${RST}"
  if [[ -n "$TAG_FILTER" ]]; then
    echo -e "  ${CLR_META}Tag filter:${RST} ${CLR_ACCENT}${TAG_FILTER}${RST}"
  fi
  echo -e ""
  echo -e "  ${CLR_META}Log levels:${RST}  ${CLR_V}█ VRB${RST}  ${CLR_D}█ DBG${RST}  ${CLR_I}█ INF${RST}  ${CLR_W}█ WRN${RST}  ${CLR_E}█ ERR${RST}  ${CLR_F}█ FTL${RST}"
  echo -e ""
  echo -e "  ${CLR_HOTKEY}[C]${RST} ${CLR_META}Clear logs${RST}    ${CLR_HOTKEY}[Q]${RST} ${CLR_META}Quit${RST}    ${CLR_META}Ctrl+C to force stop${RST}"
  echo -e "${CLR_META}  ──────────────────────────────────────────────────────${RST}"
  echo -e ""
}

# ── Handle hotkeys in the run loop ──────────────────────────────────────────
run_with_hotkeys() {
  local mode="$1"
  local value="$2"
  start_stream "$mode" "$value"

  if [[ ! -t 0 ]]; then
    wait "$STREAM_PID" 2>/dev/null || true
    return 0
  fi

  while true; do
    if [[ -z "$STREAM_PID" ]] || ! kill -0 "$STREAM_PID" 2>/dev/null; then
      wait "$STREAM_PID" 2>/dev/null || true
      STREAM_PID=""
      return 0
    fi

    local key=""
    if read -r -s -n1 -t 1 key; then
      case "$key" in
        c|C)
          "${ADB[@]}" logcat -c >/dev/null 2>&1 || true
          : > "$LOG_FILE"
          write_session_metadata
          clear_terminal
          ;;
        q|Q)
          USER_QUIT=true
          stop_stream
          echo -e "\n${CLR_META}${BOLD}  ✓ Log viewer stopped.${RST}"
          echo -e "  ${CLR_META}Full log saved:${RST} ${CLR_ACCENT}${LOG_FILE}${RST}\n"
          return 0
          ;;
      esac
    fi
  done
}

# ── Cleanup on exit ─────────────────────────────────────────────────────────
cleanup() {
  stop_stream
  jobs -p 2>/dev/null | xargs -r kill 2>/dev/null || true
  stty sane 2>/dev/null || true
  echo "" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ── Main ─────────────────────────────────────────────────────────────────────
write_session_metadata
print_banner

if [[ -n "$APP_UID" ]]; then
  echo -e "  ${CLR_META}Mode:${RST}    ${CLR_ACCENT}UID filter${RST} ${CLR_META}(uid=${APP_UID})${RST}"
  echo -e ""

  run_with_hotkeys --uid "$APP_UID"
  rc=$?

  if $USER_QUIT; then
    exit 0
  fi

  if [[ $rc -eq 0 ]]; then
    exit 0
  fi

  echo -e "${CLR_W}  ⚠ UID filter not supported on this device. Falling back to PID mode...${RST}"
fi

echo -e "  ${CLR_META}Mode:${RST}    ${CLR_ACCENT}PID tracking${RST}"
echo -e ""

last_pid=""
while true; do
  pid="$("${ADB[@]}" shell pidof -s "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"

  if [[ -z "$pid" ]]; then
    if [[ -n "$last_pid" ]]; then
      echo -e "${CLR_W}  ⏸ Process stopped. Waiting for ${PACKAGE} to start...${RST}"
      last_pid=""
    fi
    sleep 1
    continue
  fi

  if [[ "$pid" != "$last_pid" ]]; then
    echo -e "${CLR_I}${BOLD}  ▶ Attached to PID ${pid}${RST}"
    last_pid="$pid"
  fi

  run_with_hotkeys --pid "$pid"
  rc=$?

  if $USER_QUIT; then
    exit 0
  fi

  if [[ $rc -ne 0 ]]; then
    sleep 1
  fi
done
