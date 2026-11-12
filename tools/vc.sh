#!/usr/bin/env bash
# Pleos Virtual Console HTTP 조작 함수 모음 (source tools/vc.sh)
# 준비: adb forward tcp:8088 tcp:8088
B=${VC:-http://127.0.0.1:8088}
speed=0
t=0
beat() { curl -s -m2 -X POST "$B/browser/session?client=sedaecha-test" >/dev/null; }
put() { curl -s -m2 -X PUT "$B$1" >/dev/null; }
set_speed() { speed=$1; put "/vehicle/controls?speed=$1&accelerator=0&brake=0"; }
gear() { put "/vehicle/gear?gear=$1"; echo "[$t s] gear $1"; }
turn() { put "/vehicle/turn-signal?mode=$1"; echo "[$t s] turn $1"; }
tick() { beat; t=$((t + 1)); sleep 1; }
# 0.5초마다 4km/h 씩 (약 8km/h/s, 보통 운전)
ramp() { local to=$1 half=0; while [ "$speed" -ne "$to" ]; do
    if [ "$speed" -lt "$to" ]; then n=$((speed + 4)); [ $n -gt "$to" ] && n=$to; else n=$((speed - 4)); [ $n -lt "$to" ] && n=$to; fi
    set_speed $n; beat; sleep 0.5; half=$((1 - half)); [ $half -eq 0 ] && t=$((t + 1)); done; }
hold() { local s=$1; for _ in $(seq 1 "$s"); do set_speed "$speed"; tick; done; }
say() { echo "[$t s] $*"; }
