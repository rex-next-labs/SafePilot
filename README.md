# SafePilot

Pleos Connect 차량 데이터로 주차, 정비, 사고 등 운전자가 마주치는 다양한 상황을 먼저 감지하고, 차종별 매뉴얼에 맞는 대처를 음성으로 안내하며 필요한 조치를 마쳤는지까지 확인해 주는 운전 파트너 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 지금 동작하는 것 (0.1.0)

| 시나리오 | 시작 | 흐름 |
|---|---|---|
| 경사로 주차 | 기어를 P로 바꿨는데 주차 브레이크가 꺼져 있으면 앱이 먼저 알림 | 주차 브레이크 작동을 차량 데이터로 확인 → "경사진 곳인가요?" → 핸들을 도로 가장자리로 안내(도로교통법 시행령 제11조 제3항) → 완료 |
| 타이어 공기압 경고 | TPMS 경고등이 켜지면 앱이 먼저 알림 | "펑크일 수 있어요" → 주행 중이면 안전한 곳에 정차 요청, 정차를 속도 데이터로 확인 → 정비소 점검과 교체 조건 안내 |

- 차량을 조작하지 않습니다. 안내하고 차량 데이터로 결과를 확인만 합니다.
- 생성형 AI를 쓰지 않습니다. 정해진 규칙과 매뉴얼 근거로 판단합니다.
- 앱을 부를 때는 Pleos 음성 비서 Gleo AI에게 "세이프파일럿, 주차 확인해 줘"처럼 말합니다(Gleo Interface, `app/src/main/assets/schema.json`). 마이크 버튼으로도 시작할 수 있습니다.
- 앱은 상시로 듣지 않습니다. 앱이 질문한 직후 6초와, 마이크 버튼을 눌렀을 때만 듣습니다. 화면의 예/아니요 버튼으로도 답할 수 있습니다.
- 읽을 수 없는 차량 데이터는 "확인 불가"로 표시하고 운전자에게 묻습니다.

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (API 34, x86_64). 설치 방법: [Pleos 에뮬레이터 설정 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- AVD: 기기 `Pleos Connect v2.1.0`(2560x1440), RAM 4GB, Cold boot
- 빌드: JDK 17, Gradle 8.10.2(래퍼 포함), AGP 8.8.2, Kotlin 2.0.0, compileSdk/targetSdk 35, minSdk 28
- Pleos Connect SDK: Vehicle 2.3.2, SpeechToText 3.3.1, TextToSpeech 3.3.1 (Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`)
- 음성 입출력은 기기 내 처리(ON_DEVICE)로 동작하므로 Playground 키가 없어도 됩니다

## 실행 방법

### APK로 실행

1. Pleos Connect Emulator를 실행합니다.
2. APK를 설치합니다. `-g`는 런타임 권한(속도, 마이크)을 설치할 때 함께 허용합니다.

   ```bash
   adb install -r -g apk/SafePilot_release.apk
   ```

3. 런처에서 SafePilot을 엽니다. 앱은 화면 오른쪽 영역에 표시됩니다.

### 소스로 빌드

```bash
# local.properties: sdk.dir=C\:/Android/Sdk  (Android SDK 설치 위치)
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 단위 테스트
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

## 시나리오 재현 (Pleos Virtual Console)

1. Virtual Console을 엽니다.

   ```bash
   adb forward tcp:8088 tcp:8088
   ```

   브라우저에서 `http://127.0.0.1:8088/`을 엽니다. 시험하는 동안 이 화면을 열어 두어야 합니다.
2. **경사로 주차**: Safety → Telltales에서 Parking brake를 Off로 둡니다. Motion → Gear를 D에서 P로 바꿉니다. 앱이 "주차 브레이크가 꺼져 있어요."라고 말하면 Parking brake를 On으로 바꿉니다. 이후 질문에 "응"이라고 말하거나 화면의 "예"를 누릅니다.
3. **음성으로 부르기**: 에뮬레이터를 `-allow-host-audio`로 띄운 뒤 `tools/gleo.ps1`을 실행합니다(PC 마이크 연결 + 운전석 음성 버튼과 같은 동작). 화면 위쪽에 "운전석에서 듣는 중…"이 뜨면 화면을 누르지 말고 "세이프파일럿, 주차 확인해 줘"라고 말합니다.
4. **타이어 경고**: Gear D, 속도를 올린 상태에서 Telltales → TPMS를 On으로 바꿉니다. 주행 중에는 Pleos가 앱 화면을 가리고 음성 안내만 나옵니다. 브레이크로 정차하면 화면 안내가 이어집니다.

## 권한

| 권한 | 용도 |
|---|---|
| `pleos.car.permission.CAR_INFO`, `CAR_DRIVE`, `CAR_SAFETY` | 기어, 브레이크, 주행거리, 타이어, 에어백 조회 |
| `pleos.car.permission.PRIVATE_CAR_SAFETY` | 경고등(주차 브레이크, TPMS) 조회. 앱 마켓 사용 승인 여부는 확인 중이며, 없으면 주차 브레이크는 AAOS `PARKING_BRAKE_ON`으로 읽습니다 |
| `pleos.car.permission.TTS_SERVICE`, `STT_SERVICE` | 음성 안내와 음성 인식 |
| `android.car.permission.CAR_SPEED`, `CAR_POWERTRAIN` | 속도(정차 판단), 주차 브레이크 상태 |
| `android.permission.RECORD_AUDIO` | 음성 인식 |

## 구조

```
app/src/main/java/ai/safepilot/
  vehicle/   Pleos Vehicle SDK와 AAOS 데이터를 하나의 차량 상태로 통합
  engine/    상황 감지, 시나리오 규칙, 음성 의도 분류(키워드 규칙)
  session/   대화 진행 상태 머신
  voice/     Pleos TTS/STT
  ui/        Jetpack Compose 화면
docs/        앱 설계서, Pleos SDK 실측 결과
tools/vehicle-probe/   차량 데이터 실측용 앱
```

## 참고

- 설계서: [docs/app_design.md](docs/app_design.md)
- 에뮬레이터 실측 결과: [docs/sdk_measurement.md](docs/sdk_measurement.md)
- Pleos Connect SDK 라이선스에 따라 SDK 파일과 에뮬레이터 이미지는 이 저장소에 포함하지 않습니다.
