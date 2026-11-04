#!/usr/bin/env bash
# Virtual Console로 차량 값을 바꿔 가며 프로브 앱 반응을 기록한다.
# 전제: 에뮬레이터 실행 중, 프로브 앱 설치됨, Virtual Console 화면이 브라우저에 열려 있음.
set -u
ADB="${ADB:-adb}"
V=http://127.0.0.1:8088/vehicle
"$ADB" forward tcp:8088 tcp:8088 >/dev/null
put() { echo ">> $1"; curl -s -o /dev/null -w "   HTTP %{http_code}\n" -X PUT "$V/$1"; "$ADB" shell 'sleep 2'; }

"$ADB" shell am force-stop check.pleos
"$ADB" logcat -c
"$ADB" shell am start --user "$("$ADB" shell am get-current-user | tr -d '\r')" -n check.pleos/check.ProbeActivity >/dev/null
"$ADB" shell 'sleep 6'

put "gear?gear=Drive"
put "controls?speed=30"
put "controls?steering=90"
put "controls?brake=50"
put "telltales/simulator?type=PARKING_BRAKE&state=On&color=Red"
put "telltales/simulator?type=TPMS&state=On&color=Yellow"
put "lights?feature=hazard&value=true"
"$ADB" emu sensor set acceleration-uncalibrated 25:9.8:0 >/dev/null; "$ADB" shell 'sleep 1'
"$ADB" emu sensor set acceleration-uncalibrated 0:9.8:0 >/dev/null
put "controls?brake=0"
put "gear?gear=Park"
# 원래대로
put "telltales/simulator?type=PARKING_BRAKE&state=Off&color=Red"
put "telltales/simulator?type=TPMS&state=Off&color=Yellow"
put "lights?feature=hazard&value=false"
put "controls?steering=0"

"$ADB" logcat -d -s SPProbe:I | grep -E "EVT|FAIL|THROW" | grep -v -E "PERF_VEHICLE_SPEED\[area=0\] -> 0.0$"
