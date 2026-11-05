# vehicle-probe

Pleos Vehicle SDK와 AAOS 차량 데이터를 읽고 변화를 구독해 logcat `SPProbe`에 남기는 실측용 앱이다. 결과는 `docs/sdk_measurement.md`에 정리했다.

빌드 (JDK 17, Gradle 8.10.2):

    # local.properties: sdk.dir=C\:/Android/Sdk  (Android SDK 설치 위치)
    gradle :app:assembleDebug
    adb install -r -g app/build/outputs/apk/debug/app-debug.apk

값 변경 시험: Virtual Console을 브라우저에 연 상태에서 `bash vc_scenario.sh`.

`PRIVATE_CAR_SAFETY`는 경고등 API 실측용으로 선언했다. 출시용 앱에 쓸 수 있는지는 확인되지 않았다.
