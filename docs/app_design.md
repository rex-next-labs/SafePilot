# SafePilot 앱 설계서 (v1, 2026-10-30)

차량용 상황 대응 코파일럿. 운전자가 마이크 버튼을 누르고 상황을 말하면, 차량 데이터와 차량별 지식을 바탕으로 안전 조치와 대응 절차를 안내한다. **차량은 조작하지 않는다.**

- 대상 플랫폼: 현대자동차그룹 Pleos Connect (Android Automotive OS 기반)
- 제출 일정: 기획서와 선택 산출물(시연 영상, GitHub, APK)은 2026-11-15까지. 1차 합격 시 2차 PT(2026-12-03~04) 전까지 실제 동작하는 앱이 필요하다.
- 근거 문서: Pleos 개발자 문서(document.pleos.ai)와 공모전 가이드(appdevchallenge.auto-hmg.io). 2026-10-30에 조사했다. 출처는 부록 A에 있다.

## 표기 규칙

| 표기 | 의미 |
|---|---|
| [확인] | Pleos 공식 문서에서 API 이름까지 확인함 |
| [AAOS] | Android Automotive 표준 기능. Pleos 문서가 이 방식으로 읽으라고 안내함. 실차·에뮬레이터 제공 여부는 직접 시험해야 함 |
| [미검증] | 문서에서 확인하지 못함. 시험 전까지 동작한다고 가정하지 않음 |
| [없음] | 문서상 해당 API가 없음 |

---

## 1. 설계에 영향을 주는 핵심 사실

조사 결과 기획 단계의 가정과 다른 점이 있다. 아래 내용이 설계 전체의 전제가 된다.

| # | 사실 | 설계에 주는 영향 |
|---|---|---|
| 1 | **충돌 이벤트 API가 없다** [없음]. 에어백 전개 여부 API는 있다 [확인] | 충돌은 직접 알 수 없다. **충격(가속도 급변) + 정차**가 함께 일어나면 "충돌 의심"으로 보고 운전자에게 묻는다(4.1절). 가속도 센서를 쓸 수 없으면 운전자 음성("사고 났어")으로 시작한다. 에어백 전개는 큰 사고용 보조 신호다 |
| 2 | **타이어는 바퀴별 "저압 여부"만 읽을 수 있다** (`getLowTirePressure`) [확인]. 공기압 수치는 없다 [없음] | "공기압 급감"은 판단할 수 없다. "앞 오른쪽 공기압 부족 경고"까지만 말할 수 있다. 영상 UI 문구를 고쳐야 한다 |
| 3 | **속도·기어는 Pleos SDK에 없고 AAOS `CarPropertyManager`로 읽는다** [AAOS]. 에뮬레이터 Virtual Console에 기어는 있지만 속도 조절 카드는 없다 | 속도를 쓰는 기능(주행 중 여부 판단 등)은 에뮬레이터에서 시험하기 어렵다. 기어와 주차 브레이크 중심으로 설계한다 |
| 4 | **주차 브레이크는 경고등(telltale) `PARKING_BRAKE` / `ELECTRONIC_PARKING_BRAKE` 상태로 확인한다** [확인] | 경사로 주차 시나리오는 실제 데이터로 구현할 수 있다. 영상과 가장 잘 맞는 기능이다 |
| 5 | **차종·연식·트림을 직접 주는 API가 없다.** VIN만 있다 (`getVINInfo`) [확인] | VIN을 해석해 차종과 연식을 추정하고, 처음 쓸 때 운전자에게 확인받는다. 트림은 등록 단계에서 직접 고르게 한다 |
| 6 | **비상등 상태 API가 Pleos SDK에 없다** [없음]. AAOS 표준 `HAZARD_LIGHTS_STATE`가 있지만 Pleos 제공 여부는 확인하지 못했다 [AAOS][미검증] | "비상등 켜짐 확인"은 시험 전까지 운전자의 확인("켰어")으로 처리한다 |
| 7 | **LLM SDK 제공이 끝났다** [확인]. Gleo AI 연동(Gleo Interface)은 별도 승인이 필요하다 | 답변은 앱 안의 **규칙 기반 상황 엔진과 차량별 지식 베이스**로 만든다. 자유 질문 응답용 외부 LLM은 선택 기능으로 둔다 |
| 8 | **STT와 TTS는 한국어(ko-KR)를 지원하고 push-to-talk 방식으로 쓸 수 있다** [확인] | 마이크 버튼 → `request()` → 인식 결과 → 상황 판단 → TTS 안내 흐름으로 바로 구현할 수 있다 |
| 9 | **주행 중 사용 허가(Distraction Optimization)를 선언하지 않은 앱은 주차 중에만 쓸 수 있다** [확인]. 선언 방법은 문서에서 찾지 못했다 [미검증] | MVP는 정차·주차 상황 중심으로 한다(경사로 주차, 정비소, 사고 후 정차). 고속도로 긴급 정차처럼 주행 중에 써야 하는 기능은 선언 방법을 확인한 뒤 진행한다 |
| 10 | **조회 권한(`CAR_*`)은 신청 없이 쓸 수 있다. 차량 제어 SDK는 "준비 중"이다** [확인] | SafePilot은 조회만 하므로 정책과 맞는다 |

---

## 2. 기능 범위

### 2.1 MVP 시나리오

| 시나리오 | 시작 방법 | 쓰는 차량 데이터 | 안내 내용 | 구현 가능성 |
|---|---|---|---|---|
| **경사로 주차** | **자동 감지**: 기어 P 전환 + 주차 브레이크 꺼짐 (경사 정보는 읽히면 추가) | 기어 [AAOS], 주차 브레이크 경고등 [확인], 오토홀드 경고등 [확인] | 기어 P 확인 → 주차 브레이크 작동 요청 → 작동 확인 | 높음. 에뮬레이터에서 기어와 경고등을 바꿔 시험 가능 |
| **타이어 이상과 교체 상담** | **자동 감지**: 타이어 저압·TPMS 경고등 [확인] → 알림. 정비소에서는 음성 "타이어 네 개 다 바꿔야 한대" | 바퀴별 저압 여부 [확인], TPMS 경고등 [확인], 차종(VIN) [확인] | 어느 바퀴가 저압인지 → 차종별 교체 기준(지식 베이스) → "마모 깊이를 재 달라고 하세요" | 높음. 저압 바퀴 위치는 실데이터, 교체 기준은 매뉴얼 기반 지식 |
| **차량 이상(경고등)** | 경고등 변화 구독 [확인] 또는 음성 "이 경고등 뭐야?" | 경고등 약 95종의 종류, 상태, 색 [확인] | 경고등 의미 → 색과 종류에 따른 대응(즉시 정차 / 서행 후 점검 / 주행 가능) → 매뉴얼 근거 | 높음. 고장 부위를 확정 진단하지 않는다 |
| **접촉 사고 대응** | **충돌 의심 자동 감지** (충격 + 정차, 4.1절), 음성 "사고 났어", 에어백 전개 [확인] | 가속도 센서 [미검증], 속도 [AAOS], 브레이크 페달 [확인], 기어 [AAOS], 에어백 전개 [확인], 비상등 [AAOS][미검증] | 운전자 상태 확인 → 부상자 확인 → 비상등, 대피, 보험사·경찰 연락 순서 안내 | 중간. 사고 자동 감지는 불가. 음성으로 시작하는 흐름은 가능 |
| **자동 세차** | 음성 "자동 세차 들어가려고" | 기어 [AAOS], 창문·사이드미러 상태 [확인] | 차종별 세차 모드 절차(지식 베이스), 창문 닫힘 확인 | 중간. 세차 모드 절차는 차종 매뉴얼 확인 필요 |

### 2.2 MVP 이후

| 기능 | 보류 이유 |
|---|---|
| 고속도로 긴급 정차 | 주행 중 사용 허가(DO) 선언 방법이 확인되지 않았다 |
| 충돌 자동 감지 | 충돌 API가 없다. 감속도 추정은 오판 위험이 커서 쓰지 않는다 |
| Gleo AI 연동 ("SafePilot 경사로 주차 확인해 줘") | Gleo Interface 승인이 필요하고, 공모전 참가자가 받을 수 있는지 확인되지 않았다 |
| 자유 질문 응답 (외부 LLM) | 네트워크 의존, 안전 안내의 정확성 검증 부담 |

### 2.3 하지 않는 것

- 기어 변경, 주차 브레이크, 가속, 제동, 조향 등 모든 차량 제어
- 상시 음성 감지와 호출어. 반드시 마이크 버튼으로 시작한다
- 경고등 하나만 보고 고장 부위를 확정하는 진단
- 확인되지 않은 데이터를 확인된 것처럼 보여주기

---

## 3. 차량 데이터 매핑

| 정보 | 읽는 방법 | 권한 | 상태 | 비고 |
|---|---|---|---|---|
| 차종·연식 | Pleos `CarInfo.getVINInfo` → VIN 해석 | `pleos.car.permission.CAR_INFO` | [확인] | 트림은 등록 화면에서 선택 |
| 기어 | AAOS `CarPropertyManager` (`GEAR_SELECTION` / `CURRENT_GEAR`) | AAOS 파워트레인 권한 | [AAOS] | Virtual Console에서 P/R/N/D 변경 가능 |
| 속도 | AAOS `PERF_VEHICLE_SPEED` | AAOS 속도 권한 | [AAOS] | 에뮬레이터 조절 카드 없음 |
| 주차 브레이크 | Pleos `Safety` 경고등 `PARKING_BRAKE`, `ELECTRONIC_PARKING_BRAKE` | `pleos.car.permission.CAR_SAFETY` | [확인] | 경고등 ON이면 작동 중으로 해석 |
| 오토홀드 | Pleos 경고등 `AUTO_HOLD` | `CAR_SAFETY` | [확인] | |
| 경고등 전체 | Pleos `Safety.getTelltalesInfo`, `registerTelltales` (`onTelltaleInfoUpdated`) | `CAR_SAFETY` | [확인] | 상태 ON/OFF/BLINK, 색 RED/YELLOW/GREEN/BLUE/WHITE/GRAY |
| 타이어 저압 | Pleos `Tire.getLowTirePressure` (바퀴별: LEFT_FRONT 등) | `CAR_SAFETY` | [확인] | 수치 없음. 변화 구독 API 없음. 주기적으로 조회 |
| 에어백 전개 | Pleos `Safety.hasSeatAirbagBeenDeployed`, `registerSeatAirbagDeployed` | `CAR_SAFETY` | [확인] | |
| 안전벨트 미착용 | Pleos `Safety` 안전벨트 경고 | `CAR_SAFETY` | [확인] | 사고 안내 시 참고 |
| 비상등 | AAOS `HAZARD_LIGHTS_STATE` | AAOS 외부 조명 권한 | [AAOS][미검증] | 안 되면 운전자 음성 확인으로 대체 |
| 충돌 | 없음 | | [없음] | 충격 + 정차로 "충돌 의심" 판단 (4.1절), 안 되면 운전자 음성 |
| 가속도 (충격) | Android `SensorManager` `TYPE_LINEAR_ACCELERATION` / `TYPE_ACCELEROMETER` | 없음 (일반 센서) | [미검증] | Pleos 기기가 앱에 센서를 여는지 시험 필요. 에뮬레이터 가상 센서로 시험 |
| 브레이크 페달 | Pleos `Brake.getBrakePedalStatus`, `registerBrakePedalStatus` | `pleos.car.permission.CAR_DRIVE` | [확인] | 정차 판단 보조 |

공통 원칙
- 쓰기 전에 반드시 `checkXxxCapabilities`로 지원 여부를 확인한다. 차종마다 지원 API가 다르고, 문서도 모델명으로 분기하지 말라고 안내한다.
- 지원하지 않는 데이터는 화면에 "확인 불가"로 표시하고, 운전자에게 묻는 질문으로 대체한다. 예: "주차 브레이크를 거셨나요?"
- Vehicle SDK 호출은 비동기(IPC)다. 결과는 `onComplete`/`onFailed`로 받고, 콜백이 끝나기 전에 `release()`하지 않는다.
- 함수 시그니처, 반환 타입, 함수별 권한은 공개 문서에서 확인하지 못했다. 구현 첫 단계에서 SDK AAR과 Sample App 소스로 확인한다.

---

## 4. 아키텍처

```
┌──────────────────────────── SafePilot App (AAOS, Kotlin) ────────────────────────────┐
│                                                                                       │
│  UI (Jetpack Compose)                                                                 │
│   마이크 버튼 · 상황 카드 · 안내 단계 목록 · 차량 상태 표시 · 차량 등록               │
│        │ 사용자 입력                         ▲ 상태 (StateFlow)                       │
│        ▼                                     │                                        │
│  SessionController ── 대화 상태 머신 (대기 → 듣는 중 → 판단 → 안내 → 결과 확인)       │
│        │                    │                         │                               │
│        ▼                    ▼                         ▼                               │
│  VoiceIO               SituationEngine           VehicleState                         │
│   Pleos STT (ko-KR)     의도 분류 (키워드+규칙)    Pleos Vehicle SDK (경고등, 타이어,   │
│   Pleos TTS (ko-KR)     상황별 시나리오 실행       에어백, VIN)                         │
│                         안내 단계 생성             AAOS CarPropertyManager (기어, 속도)│
│                              │                    → VehicleSnapshot (Flow)            │
│                              ▼                                                        │
│                         KnowledgeBase                                                 │
│                          차종별 프로필 JSON (assets/kb/*.json)                        │
│                          매뉴얼 근거(출처 페이지) 포함                                │
│                                                                                       │
│  DrivingRestriction ── AAOS CarUxRestrictionsManager 로 주행 중 화면 제한 적용        │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

| 모듈 | 책임 | 의존 |
|---|---|---|
| `ui` | 화면 렌더링, 마이크 버튼, 큰 글씨 안내 카드 | `session` |
| `session` | 대화 상태 머신, 시나리오 진행, 타임아웃 | `voice`, `engine`, `vehicle` |
| `voice` | STT 시작·중지와 결과 스트림, TTS 출력, 오디오 포커스 | Pleos STT/TTS SDK |
| `vehicle` | 차량 데이터 구독과 조회를 `VehicleSnapshot`으로 통합, 지원 여부 관리 | Pleos Vehicle SDK, AAOS Car API |
| `engine` | 의도 분류, 시나리오 규칙, 안내 단계 생성 | `kb`, `vehicle` |
| `kb` | 차종별 지식(주차, 타이어 교체 기준, 경고등 대응, 세차 모드) 로딩 | assets |
| `restriction` | 주행 중 제한 상태에 따라 UI 단순화 | AAOS UX Restrictions |

### 4.1 충돌 의심 감지 규칙

충돌은 직접 감지할 수 없다. 대신 아래 두 조건이 차례로 맞으면 "충돌 의심"으로 판단하고 운전자에게 묻는다.

| 순서 | 조건 | 데이터 | 상태 |
|---|---|---|---|
| 1 | **충격**: 차량 진행 방향 또는 옆 방향 가속도가 짧은 시간(약 0.1초 이내)에 기준치를 넘음 | 가속도 센서 | [미검증] |
| 2 | **정차**: 충격 뒤 5초 안에 속도가 거의 0이 되고 그 상태가 2초 이상 이어짐 | 속도 [AAOS], 브레이크 페달 [확인] | |

| 상황 | 충격 | 정차 | 판단 |
|---|---|---|---|
| 과속방지턱, 포트홀 | 있음 | 없음 | 무시 |
| 급브레이크 | 없음 (서서히 감속) | 있음 | 무시 |
| 접촉 사고 | 있음 | 있음 | 충돌 의심 → "충격이 감지됐어요. 괜찮으세요?" |

- 충돌이라고 단정하지 않는다. 운전자가 "아니야"라고 하면 바로 끝낸다.
- 가속도 기준치는 실험으로 정한다. 처음에는 보수적으로(민감하게) 잡고, 오판 횟수를 보며 조정한다.
- 가속도 센서를 쓸 수 없는 환경에서는 이 기능을 끄고 음성 시작만 제공한다. 속도만으로 충돌을 판단하지 않는다.
- 주차 중 다른 차가 와서 부딪히는 경우는 범위에서 뺀다(차가 이미 멈춰 있어 조건 2가 의미 없음).

설계 원칙
- **판단은 규칙과 지식 베이스로 한다.** 안전 안내는 결과를 예측할 수 있어야 한다. 그래서 LLM이 답을 만들지 않고, 정해진 시나리오와 매뉴얼 근거를 따른다.
- **오프라인에서도 동작한다.** STT/TTS는 HYBRID 모드(서버 우선, 실패 시 기기 내 처리)를 쓰고, 지식 베이스는 앱 안에 둔다.
- **데이터가 없으면 묻는다.** 확인할 수 없는 상태는 운전자에게 질문으로 확인한다.

---

## 5. 대화 흐름

### 5.1 상태 머신

```
IDLE ──(마이크 버튼)──▶ LISTENING ──(STT 최종 결과)──▶ UNDERSTANDING
  ▲                        │ 타임아웃/오류 → "다시 말씀해 주세요"        │
  │                                                                     ▼
  │                                                   GUIDING (안내 단계 n개를 차례로)
  │                                                     │  단계마다: TTS 안내 → 결과 확인 대기
  │                                                     │  결과 확인 = 차량 데이터 변화 또는 운전자 답변
  │                                                     ▼
  └────────────────(모든 단계 완료 / 취소)──────────── DONE (요약 카드 표시)
```

- 시스템이 먼저 말을 거는 경우(경사로 주차, TPMS 경고등 켜짐, 에어백 전개)에는 알림 카드와 짧은 TTS만 낸다. 주행 중 알림은 주행 중 사용 허가 여부를 확인한 뒤 허용하고, 그 전에는 정차 후에 안내한다. 대화는 운전자가 마이크 버튼을 눌렀을 때 시작한다. 상시 음성 감지를 하지 않는다는 원칙 때문이다.

### 5.2 시나리오 예: 경사로 주차

| 단계 | SafePilot | 확인 방법 |
|---|---|---|
| 1 | (자동 감지) 기어가 P로 바뀐 순간 주차 브레이크와 오토홀드 상태 확인 | `VehicleSnapshot` 변화 구독 |
| 2 | "경사로에 주차하셨네요. 주차 브레이크가 꺼져 있어요. 걸어 주세요." (경사 정보가 없으면 "주차하셨네요") | 주차 브레이크 경고등 OFF |
| 3 | (대기) | 경고등 ON으로 바뀌면 완료. 20초 안에 변화가 없으면 다시 안내 |
| 4 | "주차 브레이크가 확인됐어요." | |

기어가 P가 아니면 단계 2 앞에 "기어를 P로 바꿔 주세요"를 넣는다. 데이터를 읽을 수 없으면 "주차 브레이크를 거셨나요?"라고 묻는다.

### 5.3 시나리오 예: 타이어 교체 상담

| 단계 | SafePilot | 근거 |
|---|---|---|
| 0 | (자동 감지) "앞 오른쪽 타이어 공기압이 낮아요. 펑크일 수 있어요. 가까운 타이어 가게로 안내할게요." | TPMS 경고등 구독 → `getLowTirePressure` |
| 1 | (정비소, 운전자 질문 후) "경고가 뜬 건 앞 오른쪽 하나예요." | 0단계에서 기록한 저압 바퀴 |
| 2 | "나머지 타이어 마모 차이가 기준 안이면 하나만 바꿔도 돼요." | 지식 베이스의 차종별 교체 기준 (매뉴얼 출처 표시) |
| 3 | "정비사에게 나머지 타이어 마모 깊이를 재 달라고 하세요." | 마모 깊이는 차량 데이터로 알 수 없음 |

- 사륜구동 차량은 마모 차이 허용 기준이 엄격하다. 지식 베이스에 기준이 없는 차종은 "제조사 기준 확인이 필요해요"라고 안내한다.

### 5.4 시나리오 예: 접촉 사고

| 단계 | SafePilot | 확인 방법 |
|---|---|---|
| 0 | (자동) 충돌 의심 감지 → "충격이 감지됐어요. 괜찮으세요?" / (수동) 운전자: "사고 났어." / (보조) 에어백 전개 | 4.1절 규칙 / STT / `registerSeatAirbagDeployed` |
| 1 | "괜찮으세요?" | 운전자 답변 |
| 2 | "다친 사람은 없나요?" | 운전자 답변. "있어"면 즉시 119 연결 안내 |
| 3 | "비상등을 켜 주세요." | AAOS `HAZARD_LIGHTS_STATE` [미검증], 안 되면 운전자 답변 |
| 4 | "안전한 곳으로 대피하세요. 차 뒤쪽 도로에 서 있지 마세요." | 운전자 답변 |
| 5 | "보험사에 연락하세요." (연락처 카드) | |

- 기어가 P가 아니면 단계 3 앞에 정차와 기어 P를 안내한다.

---

## 6. 데이터 모델 (Kotlin)

```kotlin
data class VehicleSnapshot(
    val model: VehicleModel?,                 // VIN 해석 + 등록 정보
    val gear: Known<Gear>,                    // AAOS
    val speedKmh: Known<Float>,               // AAOS
    val parkingBrakeOn: Known<Boolean>,       // telltale PARKING_BRAKE / ELECTRONIC_PARKING_BRAKE
    val autoHoldOn: Known<Boolean>,           // telltale AUTO_HOLD
    val lowTirePressure: Known<Set<Wheel>>,   // getLowTirePressure
    val airbagDeployed: Known<Boolean>,
    val hazardOn: Known<Boolean>,             // AAOS, 미검증
    val telltales: List<Telltale>,
)

sealed interface Known<out T> {
    data class Value<T>(val value: T) : Known<T>
    data object Unsupported : Known<Nothing>  // capability 없음 → 운전자에게 질문
    data object Pending : Known<Nothing>
}

enum class Situation { SLOPE_PARKING, TIRE_ISSUE, WARNING_LIGHT, ACCIDENT, CAR_WASH, UNKNOWN }

data class GuidanceStep(
    val speech: String,                       // TTS 문장
    val card: String,                         // 화면 문구 (짧게)
    val confirm: Confirm,                     // 완료 확인 방법
    val source: String?,                      // 매뉴얼 근거
)

sealed interface Confirm {
    data class VehicleCondition(val check: (VehicleSnapshot) -> Boolean, val timeoutSec: Int = 20) : Confirm
    data class DriverAnswer(val yes: List<String>, val no: List<String>) : Confirm
    data object None : Confirm
}
```

### 6.1 지식 베이스 형식 (`assets/kb/genesis_gv90.json` 예시)

```json
{
  "model": "GV90",
  "match": { "vin_wmi": ["KMT"], "vin_model_code": ["확인 필요"] },
  "parking": {
    "slope": { "steps": ["GEAR_P", "PARKING_BRAKE_ON"], "source": "GV90 사용자 매뉴얼 p.확인 필요" }
  },
  "tire": {
    "drive": "AWD",
    "single_replacement": { "allowed_if": "나머지 타이어와 마모 차이가 기준 이내", "max_tread_diff_mm": null, "source": "확인 필요" }
  },
  "car_wash": { "steps": ["확인 필요"], "source": "확인 필요" }
}
```

- 모든 안내에는 매뉴얼 출처를 남긴다. 출처를 확인하기 전 항목은 `null` 또는 "확인 필요"로 두고, 앱은 일반 안내로 대체한다.
- VIN 형식(WMI와 모델 코드 위치)은 현대차그룹 VIN 체계를 확인해 채운다. 지금은 확인되지 않았다.

---

## 7. 화면 설계

| 화면 | 내용 | 주행 중 |
|---|---|---|
| 홈 | 큰 마이크 버튼, 현재 차량(차종), 주요 상태(기어, 주차 브레이크, 경고등 수) | 마이크 버튼만 |
| 듣는 중 | 파형, "듣고 있어요", 중간 인식 결과 | 표시 |
| 안내 | 상황 제목, 단계 목록(현재 단계 강조, 완료 체크), 차량 상태 카드 | 현재 단계 한 줄만 |
| 차량 등록 | VIN으로 추정한 차종 확인, 트림 선택 | 주차 중에만 |
| 사고 연락 | 보험사, 112/119 연락 카드 | 표시 |

- 문서의 주의 분산 원칙을 따른다. 큰 터치 영역, 높은 대비, 짧은 문구, 애니메이션 최소화, 음성 우선.
- 화면 방향은 고정하지 않고 반응형으로 만든다. 문서상 portrait는 창 1개, landscape는 창 2개로 표시된다. 시험 대상은 12.9인치 세로와 14.6인치 가로 가상 디스플레이다.
- 디자인은 숏폼 영상의 SafePilot UI를 기준으로 한다(어두운 배경, 파란 강조, 빨강·초록 상태 표시).

---

## 8. 프로젝트 설정

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

// app/build.gradle.kts
android {
    compileSdk = 35
    defaultConfig { minSdk = 28; targetSdk = 35 }   // minSdk 28 은 Pleos 필수 요건
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
}
```

- 빌드 기준(Sample App): JDK 17, Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0
- Manifest 권한: `pleos.car.permission.CAR_INFO`, `pleos.car.permission.CAR_SAFETY`, `pleos.car.permission.STT_SERVICE`, `pleos.car.permission.TTS_SERVICE`, 그리고 기어·속도·비상등용 AAOS 권한
- 권한은 Pleos App Market과 협의해 승인된 범위만 쓸 수 있다. 승인되지 않은 권한을 선언하면 심사에서 떨어진다.
- STT/TTS 서버 모드는 Playground My Project에서 발급한 Client ID와 Secret으로 `registerApp()`을 호출해야 한다. 키는 코드에 넣지 않고 빌드 설정(`local.properties`)으로 주입한다.
- 앱 카테고리: Mobility Services (주행과 밀접한 차량 관리 서비스)

### 8.1 패키지 구조

```
app/src/main/java/ai/safepilot/
  ui/            HomeScreen, ListeningScreen, GuidanceScreen, RegisterScreen
  session/       SessionController, SessionState
  voice/         SttClient, TtsClient
  vehicle/       PleosVehicleSource, AaosPropertySource, VehicleRepository, VinDecoder
  engine/        IntentClassifier, scenarios/ (SlopeParking, TireIssue, WarningLight, Accident, CarWash)
  kb/            KnowledgeBase, VehicleProfile
  restriction/   DrivingRestrictionMonitor
app/src/main/assets/kb/   차종별 JSON
```

---

## 9. 테스트 계획

| 시나리오 | 에뮬레이터 Virtual Console 조작 | 확인할 것 |
|---|---|---|
| 경사로 주차 | 기어 D → P, 주차 브레이크 경고등 Off → On | 안내 순서, 상태 변화 감지, 20초 재안내 |
| 타이어 | TPMS 경고등 On | 알림 카드, 저압 바퀴 표시(`getLowTirePressure` 지원 여부 포함) |
| 경고등 | 엔진오일·ABS 등 경고등 On/Blink, 색 변경 | 색과 종류별 대응 문구 |
| 접촉 사고 | 에뮬레이터 가상 센서로 가속도 급변 입력, 비상등 카드 On | 가속도 센서가 앱에 열리는지, 충격+정차 규칙, 음성 시작 흐름, 비상등 감지 |
| 지원 안 되는 데이터 | 해당 기능 없는 상태 | "확인 불가" 표시, 질문 대체 |
| 주행 제한 | (방법 확인 필요) | 주행 중 화면 단순화 |

- 속도는 Virtual Console에서 바꿀 수 없다. 속도 관련 로직은 단위 테스트(가짜 `VehicleSnapshot`)로 확인한다.
- STT 의도 분류는 시나리오별 예문 30개 이상으로 단위 테스트한다.
- 에뮬레이터: Pleos Connect Emulator 2.1.0 (API 34 이미지), Cold boot, RAM 4GB.

---

## 10. 일정

| 기간 | 할 일 | 산출물 |
|---|---|---|
| ~11/02 | SDK AAR과 Sample App으로 API 시그니처 확인, 에뮬레이터에서 기어·경고등·타이어·비상등 읽기 시험 | 데이터 매핑 확정표 |
| ~11/08 | 경사로 주차 + 타이어 시나리오 동작, STT/TTS 연결 | 동작 데모 |
| ~11/13 | 화면 다듬기, 화면 녹화 시연 영상, README | 시연 영상, GitHub, APK (선택 제출, 가산점) |
| ~11/15 | 기획서 제출 (필수) | 접수 완료 |
| 11/23~12/02 | (1차 합격 시) 접촉 사고, 경고등, 자동 세차 시나리오 추가, 안정화 | PT용 동작 앱 |

---

## 11. 위험과 미검증 항목

| 항목 | 영향 | 대책 | 확인 시점 |
|---|---|---|---|
| Vehicle SDK 함수 시그니처와 함수별 권한 미확인 | 구현 지연 | SDK AAR과 Sample App 소스 확인 | 11/02 |
| AAOS 기어·속도·비상등이 Pleos 환경에서 읽히는지 | 경사로 주차, 사고 시나리오 | 에뮬레이터 시험. 안 되면 운전자 질문으로 대체 | 11/02 |
| `getLowTirePressure`가 바퀴별 값을 주는지 | 타이어 시나리오의 "앞 오른쪽" | 에뮬레이터 시험. 안 되면 TPMS 경고등만 쓰고 바퀴는 묻기 | 11/02 |
| 가속도 센서가 Pleos 기기에서 앱에 열리는지 | 충돌 의심 자동 감지 | 에뮬레이터 가상 센서 시험. 안 되면 음성 시작만 제공 | 11/02 |
| 주행 중 사용 허가(DO) 선언 방법 | 주행 중 기능 | 문서와 공모전 문의 | 11/08 |
| 차종별 매뉴얼 근거 (GV90 교체 기준, 세차 모드) | 안내 정확성 | 매뉴얼 확보 후 지식 베이스 작성 | 11/08 |
| VIN 해석 규칙 | 차종 자동 인식 | 실패 시 등록 화면에서 선택 | 11/08 |

---

## 12. 숏폼 영상과 맞지 않는 부분

조사 결과 영상 1차본에 문서 근거가 없는 표현이 있다. 출품 전에 고쳐야 한다.

| 영상 표현 | 문제 | 수정안 |
|---|---|---|
| 장면 2 화면 "공기압 급감 감지" | 공기압 수치 API가 없어 "급감"을 판단할 수 없다 | "공기압 부족 경고"로 변경함 (2026-10-30 반영) |
| 장면 3 "충돌 신호가 확인됐어요" | 충돌 API가 없다 | "충격이 감지됐어요. 괜찮으세요?"로 변경함 (충격 + 정차 규칙, 2026-11-02 반영). 가속도 센서 확인 전까지는 콘셉트 문구로 덮인다 |
| 장면 3 체크리스트 "비상등 켜기" 완료 체크 | 비상등 상태 읽기가 확인되지 않았다 | 콘셉트 문구로 덮이므로 유지할 수 있다. 시험에서 읽히면 실제 기능이 된다 |
| 장면 1, 2 마이크 호출 | 문서와 맞다 | 유지 |

---

## 부록 A. 출처

- Pleos Connect SDK 개요: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/
- Vehicle SDK: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro (도메인, Capability/Get/Set/Callback, 권한 페이지 포함)
- AAOS 표준 데이터 안내: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api
- 속도 FAQ: https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq
- STT: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro
- TTS: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro
- LLM SDK 종료: https://document.pleos.ai/docs/pleos-only/release-note/playground-release-note
- Gleo Interface: https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts
- 주행 규제: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy
- 주의 분산 가이드: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- 화면 구성: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model
- 에뮬레이터: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- Virtual Console: https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide
- 공모전 제출: https://appdevchallenge.auto-hmg.io/guide/submission
- 공모전 개발 환경: https://appdevchallenge.auto-hmg.io/guide/development
