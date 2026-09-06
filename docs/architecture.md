# kIkI — 아키텍처 문서

> 앱 표시 이름은 **kIkI**. 클래스명 `LangSenseAccessibilityService` 등 식별자는 유지(코드가 진실).

## 컴포넌트 흐름도

```
[블루투스 키보드 입력]
        │
        ▼
[Android 시스템]  IME 서브타입/설정 변경, 시스템 팝업 발화(Samsung One UI)
        │
        ▼
[LangSenseAccessibilityService]            # 클래스명은 식별자(불변). onServiceConnected 는 멱등(재연결 시 cleanup 먼저)
  ├── onAccessibilityEvent()
  │     TYPE_WINDOW_STATE_CHANGED        → ImeStateDetector (백스톱/삼성 팝업)
  │     TYPE_VIEW_TEXT_SELECTION_CHANGED → TextSelectionMonitor + 입력 실착 표시
  │     TYPE_VIEW_TEXT_CHANGED           → 입력 실착 표시(편집칸에 글자 들어감, isEditable 만 확인)
  │
  └── onKeyEvent()                         # 게이트 → 최근 입력 실착 검사 → (그때만) 포커스 조회(지연 평가)
        최근 입력 실착 있으면 즉시 통과 / 없을 때만 1차 활성 윈도우 → 2차 전체 윈도우 → KeyEventMonitor

[ImeStateDetector]  ── 언어 전환 감지(세 경로 병행) + 권위 소스 기반 방어(2026-07 재설계) ──
  ① BroadcastReceiver  ACTION_INPUT_METHOD_CHANGED
  ② ContentObserver    selected_input_method_subtype / default_input_method
  ③ 윈도우 이벤트 + Samsung 팝업 텍스트(ImeLocaleParser)
        │  (a) 모든 신호 → requestRecheck() 로 합침(COALESCE_MS 150ms,
        │      굶주림 방지 강제 평가 COALESCE_MAX_WAIT_MS 400ms)
        │  (b) 권위 소스 읽기(readAuthoritative) — 옵저버가 감시하는 설정값 자체를 직접 매핑,
        │      관측 키(lastAuthKey)가 실제로 바뀌었을 때만 발동(stale 원천 제거, 씹힘 해소)
        │  (c) "변경 없음"이면 강한 신호에 한해 +200/+400ms 백오프 재확인 2회(유실 방지)
        │  (d) 비권위 값(팝업 힌트/IMM 폴백)만 메아리 가드(ECHO_GUARD_MS 1000ms)
        ▼  단 1회 emitIfChanged → onLanguageChanged
[OverlayManager]
  ├── FlashOverlayView.show(lang)    → 전체화면 플래시 (windowAnimations=0)
  ├── BadgeOverlayView.update(lang)  → 상시 배지 갱신(크기/색 applyStyle)
  │     └─ 배지 탭(드래그 아님) → QuickMenuOverlayView (래디얼 메뉴: 앱/설정/토글)
  └── QuickMenuOverlayView           → 사용자 원본 assets/radialmenu.html 을 WebView 로 렌더
                                       (JS↔네이티브 브리지: KikiInit/onItemTap/onDismiss/KikiCollapse)

[KeyEventMonitor]
  포커스 없는 문자 키 N회(기본 3) → 지연 검증(WARN_VERIFY_DELAY_MS 400ms; 그 사이 입력 실착/포커스
  확인되면 취소, 저사양 오경고 방지) → OverlayManager.showNoFocusWarning() (쿨다운 2.5s)

[TextSelectionMonitor]
  드래그 선택 감지
  → HangulConverter.detectEnglishToKorean() 신뢰도 판정(mapRatio × composeRatio)
  → 신뢰도 ≥ 임계값(기본 70%) 시 ReplaceChipView.show(변환 미리보기)
  → 사용자 탭 → HangulConverter.convertEngToKor() → ACTION_SET_TEXT (실패 시 클립보드 fallback)

[HardwareKeyboardDetector] ── 터치 키보드 제외 게이트(옵션, 기본 OFF) ──
  InputManager.InputDeviceListener 로 외장 키보드 연결 실시간 감지
  → computeSoftKeyboardVisible(): TYPE_INPUT_METHOD 창 면적 ≥ 화면의 10% 면 "터치 키보드 표시 중"
  → featuresEnabled() = !excludeTouchKeyboard || !softKeyboardVisible
  → 위 3개(플래시/배지, KeyEventMonitor, TextSelectionMonitor) 모두 이 게이트를 거쳐야 동작
```

---

## 서비스 생명주기

```
앱 설치
  └── MainActivity (온보딩)
        ├── SYSTEM_ALERT_WINDOW 권한 요청
        └── 접근성 서비스 활성화 안내

접근성 서비스 활성화
  └── LangSenseAccessibilityService.onServiceConnected()
        ├── OverlayManager 초기화
        ├── BadgeOverlayView 표시 (항상)
        └── ImeStateDetector 초기화 (현재 언어 캐시)

백그라운드 상시 실행
  접근성 서비스 = 시스템이 관리
  → 배터리 최적화 예외 등록 권장 안내만 제공

접근성 서비스 비활성화
  └── onUnbind()
        └── OverlayManager.removeAll()
```

---

## 상태 관리

```kotlin
// 상태는 lastLang: String? 하나(마지막으로 발동한 언어). 과거의 ImeState(subtypeId/timestamp) 는
// 어디서도 읽히지 않으면서 발동마다 IMM IPC(currentInputMethodSubtype) 를 낭비해 제거했다.
// emitIfChanged: 이전 lastLang 과 다를 때만 발동(중복 제거 1차).
// requestRecheck: 여러 감지 신호를 COALESCE_MS 동안 합침(2차) + 발동 후 REFRACTORY_MS 불응기(3차).
// onServiceConnected 멱등화: 재연결 시 detector 중복 등록 방지(4차).
// 안티-플랩(FLAP_GUARD_MS): 직전 언어로 되돌아가는 stale 재발동 차단(5차, 렌더 가드 700ms 뒤 누수 보완).
// → 깜박임 횟수 1 지정 시 정확히 1회. emitCount + Log.d 로 실기기 검증 가능.
```

> **언어 지원**: 한국어/영어 + 기타. **일본어는 비활성화**(ImeLocaleParser/Prefs/Settings 의 ja 분기를
> 삭제 없이 주석 처리). 일본어 IME 는 전용 처리 없이 기타 언어와 같은 일반 경로(회색 `#555555`)로 흐른다.

---

## Android / One UI 버전별 호환성

| API / One UI | 이슈 | 대응 |
|---|---|---|
| API 29~30 | 특이사항 없음 | — |
| API 31~33 | 특이사항 없음 | — |
| API 34 | foregroundServiceType 필수 (접근성 서비스는 해당 없음) | — |
| API 35 | predictive back gesture 영향 없음 | — |
| One UI 3.x~7.x | 팝업 텍스트 패턴 확인 완료 | ImeLocaleParser 매핑 |
| One UI 8.x | 실기기 로그 확인 필요 (TBD) | TBD |
| One UI 9.x | **미지원 (베타)** | 별도 분기 작성 금지 |

---

## 보안 원칙

- 키스트로크 내용 수집 금지
- 선택된 텍스트는 로컬 한영타 판정에만 사용, 외부 전송 없음
- `typeViewTextChanged` 도 구독하지만 텍스트 '내용'은 읽지 않고 `source.isEditable` 여부만 확인(포커스 경고 오발동 방지)
- `onKeyEvent()`는 항상 `false` 반환 (이벤트 소비 금지)
- 접근성 이벤트 필터는 필요한 타입만 등록

```xml
<!-- accessibility_service_config.xml -->
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeViewTextSelectionChanged|typeViewTextChanged"
    android:accessibilityFeedbackType="feedbackVisual"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagRequestFilterKeyEvents"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="50" />
```
