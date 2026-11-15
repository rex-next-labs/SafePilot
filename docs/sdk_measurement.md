# Pleos SDK 실측 결과 (2026-11-04)

차량 데이터를 읽는 방법을 Pleos Connect Emulator v2.1.0에서 직접 시험한 결과다. 정리한 내용은 설계서(SafePilot_설계서) 3장 차량 데이터 매핑에 반영했다.

## 시험 환경

- Pleos Connect Emulator v2.1.0 (API 34, x86_64), AVD `Pleos_Connect_2_1`
- Vehicle SDK 2.3.2, SpeechToText 3.3.1, TextToSpeech 3.3.1
- 프로브 앱(`tools/vehicle-probe`): 값을 읽고 변화를 구독해 logcat `SPProbe`에 남긴다
- 값 변경: Pleos Virtual Console(`adb forward tcp:8088 tcp:8088` → `http://127.0.0.1:8088/`). 화면 대신 같은 HTTP 인터페이스(`PUT /vehicle/...`)로 바꿨다. Virtual Console 화면이 브라우저에 열려 있어야 동작한다(닫히면 `emulator_inactive`)
- 가속도: 에뮬레이터 콘솔 `adb emu sensor set acceleration-uncalibrated x:y:z`
- 공개 API 시그니처만 확인했다(`javap -public`). 라이선스 3.4(디컴파일·역설계 금지) 때문에 바이트코드는 보지 않았다

## 확정표

| 정보 | 읽는 방법 | 권한 | 읽기 | 변화 감지 | 에뮬레이터 조작 | 결론 |
|---|---|---|---|---|---|---|
| 기어 | Pleos `Gear.getCurrentGearSelectionState`, `registerGearSelection` | `CAR_*`(일반) | 됨 (`GEAR_PARK`) | 됨 (P→D→P) | Virtual Console Gear | **Pleos SDK로 읽는다.** AAOS `GEAR_SELECTION`도 됨(4=P, 8=D) |
| 속도 | AAOS `PERF_VEHICLE_SPEED` | `android.car.permission.CAR_SPEED`(런타임 권한) | 됨, **단위 m/s** | 됨 | Virtual Console `controls?speed=`(km/h 입력, 서서히 감속) | 정차 판단 가능. km/h 변환 필요 |
| 주차 브레이크 (경고등) | Pleos `Safety.getTelltalesInfo`, `registerTelltales` → `PARKING_BRAKE`, `ELECTRONIC_PARKING_BRAKE` | **`PRIVATE_CAR_SAFETY`** | 됨 | 됨 (On/Off 즉시) | Virtual Console Telltales | `CAR_SAFETY`만으로는 `SecurityException`. 앱 마켓 승인 여부 확인 필요 |
| 주차 브레이크 (상태값) | AAOS `PARKING_BRAKE_ON` | `android.car.permission.CAR_POWERTRAIN` | 됨 (`false`) | 구독 됨 | **조작 불가** (경고등을 켜도 바뀌지 않음) | 일반 권한으로 읽힌다. 실차 대비 1순위 후보지만 에뮬레이터 시연에는 못 쓴다 |
| 오토홀드 | 경고등 `AUTO_HOLD` | `PRIVATE_CAR_SAFETY` | 경고등 목록에 있음 | — | Telltales | 주차 브레이크와 같은 조건 |
| TPMS 경고 | 경고등 `TPMS` | `PRIVATE_CAR_SAFETY` | 됨 (노랑) | 됨 | Telltales | 타이어 시나리오의 시작 신호로 쓸 수 있다 |
| 바퀴별 저압 | Pleos `Tire.getLowTirePressure(WheelArea)` | `CAR_SAFETY` | 됨, 그러나 **값이 137.0 (모든 바퀴)** | 구독 API 없음 | — | 저압 여부가 아니라 **저압 기준값(kPa)** 이다(VHAL `CRITICALLY_LOW_TIRE_PRESSURE`) |
| 바퀴별 공기압 | Pleos `Tire.getTirePressure(WheelArea)`, `registerTirePressure` | `PRIVATE_CAR_SAFETY` | **에뮬레이터 미지원** (`TIRE_PRESSURE` 설정 없음) | 미지원 | Virtual Console `tire-pressure`가 있지만 앱까지 오지 않음 | 공기압 수치 API는 있다. 실차에서만 확인 가능. 기준값과 비교하면 "어느 바퀴"를 판단할 수 있다 |
| 비상등 | Pleos API 없음(`TurnSignal`은 OFF/LEFT/RIGHT뿐). AAOS `HAZARD_LIGHTS_STATE` | AAOS는 `CAR_EXTERIOR_LIGHTS`(일반 앱에 부여 안 됨) | **읽을 수 없음** | — | Virtual Console Hazard(켜도 앱 반응 없음) | **운전자에게 묻는다.** 확정 |
| 방향지시등 | Pleos `TurnSignal.getCurrentTurnSignalState` | `CAR_LIGHTS` | 됨 (OFF) | 됨 | Turn Signal State | 사고 시나리오에는 쓰지 않음 |
| 핸들 각도 | Pleos `SteeringWheel.getCurrentSteeringWheelAngle`, `registerSteeringWheelAngle` | `CAR_*`(일반) | 됨, 범위 −470~500도 | 됨 | `controls?steering=` | **부호가 반대**: Virtual Console에서 +90을 넣으면 앱에는 −90이 온다. 실차 부호 규약 확인 필요 |
| 브레이크 페달 | Pleos `Brake.getCurrentBrakePedalStatus`, `registerBrakePedalStatus` | `CAR_DRIVE` | 됨 | 됨 (true/false) | `controls?brake=`(%) | 정차 판단 보조로 쓴다 |
| 주행거리 | Pleos `Odometer.getCurrentDistanceDriven` | `CAR_DRIVE` | 됨 (0.0) | 구독 API 있음 | Odometer | 정비 시기 판단에 쓴다 |
| 에어백 전개 | Pleos `Safety.hasSeatAirbagBeenDeployed(SeatArea)`, `registerSeatAirbagDeployed` | `CAR_SAFETY` | **에뮬레이터 미지원** | 미지원 | 카드 없음 | API는 있다. 에뮬레이터에서는 "확인 불가" 경로만 시험 가능 |
| 엔진오일·변속기오일·브레이크액 레벨 | Pleos `Safety.get...LevelState` (LOW/HIGH/NORMAL/ERROR/CRITICALLY_LOW) | `CAR_SAFETY` | **에뮬레이터 미지원** | 미지원 | 카드 없음 | 2단계 정비 시나리오는 "확인 불가" 경로로 시연 |
| VIN | Pleos `CarInfo.getVINInfo` | `CAR_INFO` | 됨, 값은 `"invalid"` | — | — | 에뮬레이터에서는 차종 추정 불가. 차량 등록 화면에서 직접 고르게 한다 |
| 지원 기능 목록 | Pleos `CarInfo.getCapabilityInfo` | **`PRIVATE_CAR_INFO`** | 됨 (309개 키) | — | — | 개발 참고용. 앱에서는 기능별 `check...Capabilities`를 쓴다 |
| 가속도 | Android `SensorManager` | 없음 | `TYPE_LINEAR_ACCELERATION`(10), `TYPE_ACCELEROMETER`(1) **없음**. `TYPE_ACCELEROMETER_UNCALIBRATED`(35)만 있음 | 됨 | `adb emu sensor set acceleration-uncalibrated 25:9.8:0` → 앱에 25.00 도착 | 충격 감지는 10 → 1 → 35 순서로 있는 센서를 쓴다. 에뮬레이터 시험은 35로 한다. 실차 제공 여부는 미확인 |

## 설계에 주는 영향

1. **경고등을 쓰려면 `PRIVATE_CAR_SAFETY`가 필요하다.** 공식 문서의 권한 표에는 `CAR_SAFETY`만 나온다. `PRIVATE_` 권한을 공모전 앱이 써도 되는지, 앱 마켓 심사를 통과하는지는 확인되지 않았다. 문의가 필요하다.
   - 쓸 수 있으면: 주차 브레이크와 TPMS 모두 경고등으로 감지하고, 에뮬레이터에서 바로 시연할 수 있다.
   - 쓸 수 없으면: 주차 브레이크는 AAOS `PARKING_BRAKE_ON`(일반 권한)으로 읽는다. 다만 에뮬레이터에서는 값을 바꿀 수 없어 시연이 어렵다. TPMS는 대안이 없다(바퀴별 공기압도 `PRIVATE_CAR_SAFETY`).
2. **기어는 Pleos SDK로 읽는다.** AAOS를 쓰지 않아도 된다.
3. **비상등은 운전자에게 묻는다.**
4. **타이어 "어느 바퀴" 판단**은 실차에서 `getTirePressure`가 될 때만 가능하다(기준값 137kPa와 비교). 에뮬레이터 시연은 TPMS 경고등 + "어느 바퀴인지 확인해 보세요" 안내로 한다.
5. **충격 감지는 에뮬레이터에서 시험할 수 있다.** 보정 전 가속도 센서에 값을 넣는 방식이다. 속도도 Virtual Console로 바꿀 수 있어 "충격 + 정차" 규칙을 끝까지 시험할 수 있다.
6. **에어백, 오일 레벨은 에뮬레이터가 지원하지 않는다.** 앱은 지원 여부를 먼저 확인하고 "확인 불가" 경로로 동작해야 한다. 시연에서도 그 경로가 보인다.
7. **핸들 각도 부호**를 실차 규약과 맞춰야 한다. 경사로 안내는 최종 확인을 운전자 답변으로 하므로 MVP에는 영향이 없다.

## 앱 실행 중 확인한 것 (2026-11-05, SafePilot 0.1.0)

- **주행 중에는 Pleos가 앱 화면을 막는다.** 기어 D, 60km/h에서 앱 화면 대신 "주행 중에는 사용할 수 없습니다"가 뜬다. 앱 프로세스와 음성 출력(TTS)은 계속 동작했다. 주행 중 사용 허가(Distraction Optimization) 선언 전까지 화면 안내는 정차 후에만 보인다는 설계 가정이 맞았다
- **STT/TTS 권한**: `pleos.car.permission.TTS_SERVICE`, `STT_SERVICE`가 없으면 `SecurityException`
- **STT/TTS 초기화 순서**: `initialize()`가 성공한 뒤에 `setLocale`, `addEventListener`를 불러야 한다. 먼저 부르면 SDK 내부 `lateinit` 오류
- **기기 내 처리(ON_DEVICE)** 로 Playground 키 없이 TTS가 동작한다(에뮬레이터). STT도 초기화는 되지만, 에뮬레이터에 실제 음성을 넣어 인식을 확인하지는 않았다
- Virtual Console은 브라우저 화면이 열려 있어야 한다. 다른 페이지로 가거나 탭이 오래 쉬면 `emulator_inactive`(HTTP 503)

## Gleo AI 연동 (2026-11-05)

- `assets/schema.json`을 넣은 앱을 `adb install`하면 Gleo(`[GAA] [Schema] Loaded schema.json from ai.safepilot`)가 바로 읽어 등록한다. 에뮬레이터에서는 승인 절차 없이 등록됐다
- Gleo와 같은 형식의 Intent(`am start -a CHECK_PARKING_SAFETY -p ai.safepilot --es ACTION_PARAM '{}'`)로 앱이 안내를 시작하고 `APP_ACTION_RESPONSE`를 보내는 것을 확인했다
- 앱이 꺼져 있다가 호출로 켜지면 차량 값과 TTS가 아직 준비되지 않았다. 안내 시작 전에 최대 3초 기다리도록 했다
- **Gleo는 Intent action 앞에 패키지 이름을 붙여 보낸다**(`ai.safepilot.CHECK_PARKING_SAFETY`). 공식 문서 예시(`CHECK_PARKING_SAFETY`)와 다르다. 처음에 문서대로 등록했다가 "No Activity found to handle Intent"로 실패했고, Gleo는 "앱 연결에 문제가 있어서 말씀하신 것을 처리하지 못했어요."라고 답했다. 매니페스트에 두 형식을 모두 등록해 해결했다
- 실제 음성 호출: Gleo가 "세이프 파일럿 주차 확인해줘"를 인식하고 LLM이 `CHECK_PARKING_SAFETY`를 골랐다(로그 `toolCalls`). Gleo 음성 인식은 서버 STT를 쓴다
- 에뮬레이터에서 Gleo 띄우기: `adb shell cmd voiceinteraction show`(운전석 음성 버튼 짧게 누름과 같음). Gleo가 듣는 중에 화면을 누르면 닫힌다
- 에뮬레이터 PC 마이크: `-allow-host-audio`로 띄운 뒤 `adb emu avd hostmicon`을 해야 실제 소리가 들어간다. 안 하면 무음이 들어가 STT가 아무것도 인식하지 못한다(`tools/gleo.ps1`에 포함)

## 남은 확인 사항

- `PRIVATE_CAR_SAFETY` 사용 가능 여부 (공모전 운영사 또는 Pleos 문의)
- 실차에서 `PARKING_BRAKE_ON`, `getTirePressure`, 에어백, 오일 레벨, 가속도 센서 제공 여부
- Virtual Console 타이어 공기압 항목이 앱에 전달되지 않는 이유(에뮬레이터 이미지 한계로 보임)
- STT 실제 인식 품질(PC 마이크를 에뮬레이터에 연결해 시험)
- 주행 중 사용 허가 선언 방법
