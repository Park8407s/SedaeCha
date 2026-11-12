#!/usr/bin/env bash
# Pleos Virtual Console 로 약 3분 직접 운전 (데모 모드 기준 시험용).
# 출발 → 신호 대기 → 우측 깜빡이 → 정체(20km/h) → 급제동 → 신호 대기 → 도착(P)
#
# 준비: adb forward tcp:8088 tcp:8088   (Virtual Console 웹 페이지는 닫아 둔다: 페이지의 페달 시뮬레이션이 속도를 덮어씀)
# 실행: bash tools/drive_scenario.sh
set -u
B=${VC:-http://127.0.0.1:8088}
speed=0
t=0

beat() { curl -s -m2 -X POST "$B/browser/session?client=sedaecha-scenario" >/dev/null; }
put() { curl -s -m2 -X PUT "$B$1" >/dev/null; }
set_speed() { speed=$1; put "/vehicle/controls?speed=$1&accelerator=0&brake=0"; }
gear() { put "/vehicle/gear?gear=$1"; echo "[$t s] gear $1"; }
turn() { put "/vehicle/turn-signal?mode=$1"; echo "[$t s] turn $1"; }
tick() { beat; t=$((t + 1)); sleep 1; }
# 0.5초마다 4km/h 씩 목표 속도로 (약 8km/h/s, 보통 운전)
ramp() { local to=$1 half=0; while [ "$speed" -ne "$to" ]; do
    if [ "$speed" -lt "$to" ]; then n=$((speed + 4)); [ $n -gt "$to" ] && n=$to; else n=$((speed - 4)); [ $n -lt "$to" ] && n=$to; fi
    set_speed $n; beat; sleep 0.5; half=$((1 - half)); [ $half -eq 0 ] && t=$((t + 1)); done; }
hold() { local s=$1; for _ in $(seq 1 "$s"); do set_speed "$speed"; tick; done; }

beat; beat
set_speed 0; gear Park; hold 3
gear Drive; echo "[$t s] 출발"
ramp 40; hold 34                     # 말 1
echo "[$t s] 정차 (신호 대기)"; ramp 0; hold 16
echo "[$t s] 출발"; ramp 45; hold 6
turn BlinkRight; hold 5; turn Off    # 말 2 중 깜빡이
hold 12
echo "[$t s] 정체 20km/h"; ramp 20; hold 30
echo "[$t s] 정체 풀림"; ramp 50; hold 8
echo "[$t s] 급제동 50→30"; set_speed 30; tick; hold 20
echo "[$t s] 정차 (신호 대기)"; ramp 0; hold 16
echo "[$t s] 서행"; ramp 6; hold 3; ramp 0; hold 2
gear Park; echo "[$t s] 도착"; hold 3
