# Pleos SDK 실측 결과 (2026-11-04)

설계서 3장(차량 데이터 매핑)의 [미검증] 항목을 Pleos Connect Emulator v2.1.0에서 직접 시험한 결과다. 이 문서가 설계서 3장보다 우선한다.

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
| 기어 | Pleos `Gear.getCurrentGearSelectionState`, `registerGearSelection` | `CAR_*`(일반) | 됨 (`GEAR_PARK`) | 됨 (P→D→P) | Virtual Console Gear | **Pleos SDK로 읽는다.** 설계서의 "기어는 AAOS로만"은 틀렸다. AAOS `GEAR_SELECTION`도 됨(4=P, 8=D) |
| 속도 | AAOS `PERF_VEHICLE_SPEED` | `android.car.permission.CAR_SPEED`(런타임 권한) | 됨, **단위 m/s** | 됨 | Virtual Console `controls?speed=`(km/h 입력, 서서히 감속) | 정차 판단 가능. km/h 변환 필요 |
| 주차 브레이크 (경고등) | Pleos `Safety.getTelltalesInfo`, `registerTelltales` → `PARKING_BRAKE`, `ELECTRONIC_PARKING_BRAKE` | **`PRIVATE_CAR_SAFETY`** | 됨 | 됨 (On/Off 즉시) | Virtual Console Telltales | `CAR_SAFETY`만으로는 `SecurityException`. 앱 마켓 승인 여부 확인 필요 |
| 주차 브레이크 (상태값) | AAOS `PARKING_BRAKE_ON` | `android.car.permission.CAR_POWERTRAIN` | 됨 (`false`) | 구독 됨 | **조작 불가** (경고등을 켜도 바뀌지 않음) | 일반 권한으로 읽힌다. 실차 대비 1순위 후보지만 에뮬레이터 시연에는 못 쓴다 |
| 오토홀드 | 경고등 `AUTO_HOLD` | `PRIVATE_CAR_SAFETY` | 경고등 목록에 있음 | — | Telltales | 주차 브레이크와 같은 조건 |
| TPMS 경고 | 경고등 `TPMS` | `PRIVATE_CAR_SAFETY` | 됨 (노랑) | 됨 | Telltales | 타이어 시나리오의 시작 신호로 쓸 수 있다 |
| 바퀴별 저압 | Pleos `Tire.getLowTirePressure(WheelArea)` | `CAR_SAFETY` | 됨, 그러나 **값이 137.0 (모든 바퀴)** | 구독 API 없음 | — | 저압 여부가 아니라 **저압 기준값(kPa)** 이다(VHAL `CRITICALLY_LOW_TIRE_PRESSURE`) |
| 바퀴별 공기압 | Pleos `Tire.getTirePressure(WheelArea)`, `registerTirePressure` | `PRIVATE_CAR_SAFETY` | **에뮬레이터 미지원** (`TIRE_PRESSURE` 설정 없음) | 미지원 | Virtual Console `tire-pressure`가 있지만 앱까지 오지 않음 | 공기압 수치 API는 있다(설계서의 "수치 없음"은 틀림). 실차에서만 확인 가능. 기준값과 비교하면 "어느 바퀴"를 판단할 수 있다 |
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
3. **비상등은 운전자에게 묻는다.** 설계서의 대체안이 확정됐다.
4. **타이어 "어느 바퀴" 판단**은 실차에서 `getTirePressure`가 될 때만 가능하다(기준값 137kPa와 비교). 에뮬레이터 시연은 TPMS 경고등 + "어느 바퀴인지 확인해 보세요" 안내로 한다.
5. **충격 감지는 에뮬레이터에서 시험할 수 있다.** 보정 전 가속도 센서에 값을 넣는 방식이다. 속도도 Virtual Console로 바꿀 수 있어 "충격 + 정차" 규칙을 끝까지 시험할 수 있다.
6. **에어백, 오일 레벨은 에뮬레이터가 지원하지 않는다.** 앱은 지원 여부를 먼저 확인하고 "확인 불가" 경로로 동작해야 한다. 시연에서도 그 경로가 보인다.
7. **핸들 각도 부호**를 실차 규약과 맞춰야 한다. 경사로 안내는 최종 확인을 운전자 답변으로 하므로 MVP에는 영향이 없다.

## 남은 확인 사항

- `PRIVATE_CAR_SAFETY` 사용 가능 여부 (공모전 운영사 또는 Pleos 문의)
- 실차에서 `PARKING_BRAKE_ON`, `getTirePressure`, 에어백, 오일 레벨, 가속도 센서 제공 여부
- Virtual Console 타이어 공기압 항목이 앱에 전달되지 않는 이유(에뮬레이터 이미지 한계로 보임)
