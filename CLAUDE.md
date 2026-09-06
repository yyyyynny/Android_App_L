# kIkI — CLAUDE.md

> 이 파일은 Claude Code가 프로젝트를 이해하고 작업하기 위한 핵심 가이드입니다.
> 모든 작업은 이 파일의 명세와 `docs/` 하위 문서를 기준으로 수행하십시오.
> ⚠️ **문서와 코드가 다르면 코드가 진실(source of truth)** 입니다.

---

## 프로젝트 개요

**앱 표시 이름**: kIkI (발음 "키키")
**패키지명 / applicationId**: `com.langsense.app` — *식별자라 불변. 표시 이름만 kIkI 로 변경*
**내부 코드 식별자**: 클래스 `LangSenseAccessibilityService`, 리소스 `Theme.LangSense` 등은 식별자라 유지(표시 텍스트만 교체)

블루투스 키보드와 Android 태블릿을 함께 사용할 때 발생하는 두 가지 문제를 해결하는 접근성 기반 유틸리티 앱.

**문제 1**: 한/영 전환이 됐는지 인식하지 못하고 그대로 입력함  
**문제 2**: 영어 자판 상태에서 한국어를 치면 "dlfjgrp" 같은 영타 문자가 입력됨 (한영타)

---

## 지원 범위

| 항목 | 값 |
|---|---|
| 최소 SDK | API 29 (Android 10) |
| 최대 SDK | API 35 (Android 15 / 16) |
| **제외** | API 36+ (Android 17 베타 — 명세 불안정, 추후 별도 대응) |
| 주 타겟 기기 | Samsung Galaxy Tab S6 Lite, Galaxy Tab S9 FE+ |
| One UI 버전 | One UI 3.x ~ **8.x (최대 8.5)** |
| **One UI 제외** | One UI 9.x (베타 테스트 중 — 추후 별도 대응) |

---

## 기술 스택

- **언어**: Kotlin 2.x
- **빌드**: Gradle + AGP 8.x
- **최소 의존성 원칙**: 외부 라이브러리 최소화 (AndroidX Core, Lifecycle만 허용)
- **핵심 Android 컴포넌트**:
  - `AccessibilityService` — 키 이벤트/텍스트 선택/윈도우 이벤트 감지 핵심
  - `WindowManager` — 오버레이 UI
  - `InputMethodManager` — 현재 IME 서브타입(언어) 읽기
  - `BroadcastReceiver` (`ACTION_INPUT_METHOD_CHANGED`) + `ContentObserver`
    (`selected_input_method_subtype` / `default_input_method`) — **IME 언어 전환 즉시 감지(주 경로)**
  - `TYPE_WINDOW_STATE_CHANGED` + Samsung 팝업 텍스트 — 백스톱/삼성 내부 토글 보조 경로

### 빌드/툴체인 버전(고정)

> CI(`.github/workflows/ci.yml`)와 로컬 빌드가 동일하게 쓰는 고정 버전. 변경 시 이 표와 CI 를 함께 갱신.

| 항목 | 버전 | 비고 |
|---|---|---|
| JDK | **17** (Temurin) | AGP 8.x / Gradle 8.11.1 요구 |
| Gradle | **8.11.1** | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | **8.7.3** | `gradle/libs.versions.toml` — ⚠️ **9.x 는 Gradle 9.1+ 요구**(하단 참조), 별도 작업으로 보류 |
| compileSdk / targetSdk | **35** | minSdk 29 |
| build-tools | **35.0.0** | CI 에서 `sdkmanager` 로 설치 |
| Node.js | **24** | 프로젝트 표준(JS 기반 도구/스크립트 작성 시 24 기준) |

- CI 사용법·실패 진단·브랜치 보호 설정은 `docs/CI_가이드.md` 참조.
- **AGP 를 9.x 로 못 올리는 이유 + 그 때문에 `coreKtx`/`lifecycle` 도 상한선이 걸린 이유**는
  `gradle/libs.versions.toml` 상단 주석에 상세 기록(2026-07, 실제 빌드로 검증됨). AGP 를 9.x 로
  올리는 순간 그 두 제약도 함께 풀리니, 나중에 AGP 업그레이드 시 같이 처리할 것.

---

## 핵심 기능 명세

> 상세 스펙은 `docs/features.md` 참조

### Feature 1: 언어 전환 감지 플래시

IME 언어가 변경될 때 전체 화면에 플래시 오버레이를 표시하여 사용자에게 인지시킴.

- **감지 방법(코드 기준)**: `ImeStateDetector` 가 세 경로를 병행한다.
  1. `BroadcastReceiver` — `ACTION_INPUT_METHOD_CHANGED`
  2. `ContentObserver` — `selected_input_method_subtype` / `default_input_method` 변경
     (한/영 토글 시 시스템 설정이 즉시 바뀌므로 화면 변화 없이도 감지)
  3. `TYPE_WINDOW_STATE_CHANGED` + Samsung One UI 팝업 텍스트(백스톱 / 삼성 내부 토글)
- **권위 소스 읽기**: 현재 언어는 `imm.currentInputMethodSubtype`(전환 직후 stale 가능)가 아니라
  **옵저버가 감시하는 설정값 그 자체**(`selected_input_method_subtype` 해시 + `default_input_method`)를
  직접 읽어 서브타입 목록과 매핑(`readAuthoritative`, IME id 기준 목록 캐시). 권위 값은 **관측 키가
  실제로 바뀌었을 때만** 발동 근거가 되고, 매핑 불가 기기만 IMM 폴백(비권위, `ECHO_GUARD_MS`=1000ms
  메아리 가드 적용)으로 흐른다. → stale 재발동(2회 깜박임)과 과잉 가드(반응 씹힘)를 동시에 해소.
- **중복 발화 합치기(debounce/coalesce)**: 한 번의 전환이 위 경로에서 여러 신호를 만들기 때문에,
  모든 신호를 단일 예약으로 합쳐 마지막 신호 후 `COALESCE_MS`(150ms) 시점에 **한 번만** 평가
  (신호가 끝없이 이어져도 첫 신호 후 `COALESCE_MAX_WAIT_MS`=400ms 에 강제 평가 — 굶주림 방지).
  평가가 "변경 없음"이면 강한 신호(브로드캐스트/옵저버/팝업)에 한해 +200/+400ms 백오프로 최대
  2회 재확인해 저사양 기기의 늦은 설정 전파로 전환이 조용히 유실되지 않게 한다.
  덕분에 깜박임 횟수 1 지정 시 정확히 1회만 깜박인다(이전엔 신호 중복으로 2회 이상 깜박임).
- 플래시 지속 시간: **100~500ms 설정 가능(기본 200ms)**, 깜박임 횟수 1~5(기본 1)
- 오버레이 추가 시 **윈도우 enter/exit 애니메이션 제거**(`windowAnimations=0`)로 색이 좌→우로
  채워지지 않고 전체 화면이 한 프레임에 꽉 차게 나타남(페이드아웃은 뷰 알파 애니메이션).
- 언어별 배경색(불투명도 85%, 설정에서 #RRGGBB 로 변경 가능):
  - 한국어 (`ko`) → `#CC2D2D`
  - English (`en`) → `#1A6EBD`
  - 기타 → `#555555`
  - ~~日本語 (`ja`) → `#2D8C4E`~~ — **[일본어 비활성화]** 코드 주석 처리(아래 "주의 사항" 참조)
- 중앙 텍스트: 언어명 표시 (`한국어` / `English` / locale 코드)

### Feature 2: 상시 언어 표시 배지

화면 모서리에 현재 입력 언어를 항상 표시하는 소형 오버레이.

- 구현: `WindowManager` + `TYPE_APPLICATION_OVERLAY`
- 위치: 드래그로 변경, 자동 저장 (기본값: 우하단). 표시/회전 시 현재 화면 경계로 보정
  (`ensureOnScreen` — 저장값은 유지, 화면 밖에 붙어 복구 불가가 되는 문제 방지)
- **커스터마이즈(설정에서)**:
  - 표시 ON/OFF
  - 크기 3단계: 소 / **중(기본=기존 외형)** / 대
  - 배경색·글씨색: 플래시와 동일한 공용 색 선택 UI(32색 팔레트 + #RRGGBB 입력) 재사용.
    배경은 `BADGE_BG_ALPHA`(0.8) 적용 → 기본값이면 기존 `#CC000000` 과 동일, 둥근 모서리 6dp.
- 표시 내용: `한` / `EN` (기타 언어는 locale 앞 2자리 대문자) — ~~`日`~~ [일본어 비활성화]

### Feature 3: 포커스 없는 상태 키 입력 경고

입력 포커스가 없는 상태에서 문자 키 입력이 임계값(기본 3회) 이상 감지되면 경고 오버레이 표시.

- 감지: `AccessibilityService.onKeyEvent()` + 포커스 노드 확인
- **오발동 방지(최우선)**: 글자가 실제 입력칸에 들어가면 편집 노드가 `typeViewTextChanged`/
  `typeViewTextSelectionChanged` 이벤트를 낸다. 최근(`RECENT_INPUT_MS`=1.2s) 그런 "입력 실착"이
  있었으면 포커스 조회 결과와 무관하게 포커스 있음으로 간주해 경고를 억제(포커스가 정말 없으면
  그 이벤트가 없으므로 진짜 경고는 유지). → "입력은 되는데 경고가 뜨는" 오발동 해결.
- **저사양 최적화**: 기능 OFF 면 `syncServiceInfo()` 가 키 필터 플래그(키당 system_server↔앱 Binder
  동기 왕복 2회)와 텍스트 변경 구독을 아예 내려 콜백이 오지 않는다. 키 평가 스레드도 ON 일 때만 존재.
  ON 이면 포커스 조회는 비싸므로 **ACTION_DOWN + 비반복 + 실제 문자 키 + 최근 입력 실착 없음** 게이트를
  통과한 뒤에만 백그라운드에서 평가하고, 조회 결과는 150ms 캐시(`FOCUS_PROBE_CACHE_MS`; 지연 검증은
  캐시 미사용). 편집 여부 확인(노드 IPC)은 결과와 무관하게 300ms 당 1회로 상한. 1차는 활성 윈도우만 보는 저비용 조회,
  없을 때만 2차로 전체 윈도우 순회(일시적 null 방어). 모디파이어/반복/단축키/기능 OFF 에선 노드 트리를 안 건드림.
- **발동 전 지연 검증(`WARN_VERIFY_DELAY_MS`=400ms)**: 저사양 기기는 글자가 실제 입력돼도 그 확인
  이벤트(text/selection 변경)와 포커스 노드 갱신이 수백 ms 늦게 온다. 임계값 도달 즉시 경고하면 그
  지연된 확인이 오기 전에 오경고가 뜨므로, 바로 띄우지 않고 이 시간 뒤 재확인한다. 그 사이 입력 실착이
  확인되거나 포커스가 잡히면 경고를 취소, 진짜 포커스가 없으면(확인 이벤트 안 옴) 정상 발동. →
  "입력은 되는데 저사양에서 가끔 경고가 뜨는" 잔여 오발동 해결.
- 표시: `선택되지 않음` 텍스트 오버레이 (Feature 1과 동일한 플래시 방식, 재경고 쿨다운 2.5s)
- 카운터는 포커스 획득 시 초기화. `onKeyEvent` 는 항상 `false` 반환(이벤트 절대 미소비)
- 텍스트 '내용'은 읽지 않음(`source.isEditable` 여부만 확인)

### Feature 4: 한영타 감지 + 인라인 교체

드래그로 선택한 텍스트가 "영타로 입력된 한국어"(한영타) 패턴일 때,
선택 영역 근처에 작은 **"교체?"** 버튼을 띄워 탭 한 번으로 올바른 한국어로 교체.

**동작 흐름**:
1. `TYPE_VIEW_TEXT_SELECTION_CHANGED` 이벤트 감지
2. 선택된 텍스트 추출
3. `HangulConverter.detectEnglishToKorean(text)` 호출 — 한영타 신뢰도 판정
4. 신뢰도 ≥ 70% 이면 화면 상단에 `"교체?"` 미니 오버레이 버튼 표시
5. 사용자가 "교체?" 탭 → `HangulConverter.convertEngToKor(text)` 실행
6. `AccessibilityNodeInfo.performAction(ACTION_SET_TEXT, bundle)` 으로 교체
   - 실패 시 fallback: 클립보드에 복사 후 붙여넣기
7. 버튼 **2초 후 자동 소멸** (시스템 텍스트 선택 팝업 방해 최소화)

**한영타 판정 기준** (`HangulConverter`):
- 입력 텍스트의 각 영문자를 두벌식 자모로 변환 후 한글 조합 시도
- 조합 성공한 음절 비율 ≥ 70% → 한영타로 판정
- 실제 영어 단어일 경우 조합 실패율이 높아 오탐 방지
- **영어 상용어 스톱리스트(필수)**: 두벌식에서 자음-모음-자음 배열이 되는 흔한 영단어는 한글로
  100% 조합돼(the→솓, and→뭉, for→랙, with→쟈소, work→재가) 조합률만으로는 최고 신뢰도가 나온다.
  길이·음절수 같은 구조 신호로는 진짜 한영타(dkssud=안녕)와 분리되지 않으므로 `HangulConverter`
  의 `ENGLISH_STOPWORDS`(고빈도 영단어 ~150개)로 거른다 — 선택이 상용어(들)로만 이뤄졌으면
  신뢰도 0(임계값 설정과 무관하게 차단), 혼합 선택("dkssud the")은 억제하지 않는다.
  대신 상용어와 형태가 겹치는 단음절 한영타(go=해, to=새, did=양 …)는 자동 제안이 안 뜬다 —
  영문을 읽던 중 칩이 튀어나오는 오탐의 체감 비용이 훨씬 크기 때문에 감수한 트레이드오프.
- 선택 길이 상한 `MAX_SELECTION`(200자): 전체 선택 시 메인 스레드 대량 변환 + `ACTION_SET_TEXT`
  Binder 트랜잭션 한도 초과를 막는다.
- 교체 성공 후 `ACTION_SET_SELECTION` 으로 커서를 교체 자리 뒤에 되돌린다(전체 텍스트 교체라
  기본적으로 커서가 문서 끝으로 튄다). 클립보드 폴백은 **선택 영역 설정에 성공했을 때만**
  붙여넣는다 — 실패 시 붙여넣으면 원문 옆에 한글이 덧붙어 텍스트가 파손된다.

**"교체?" 버튼 스펙(코드 기준)**:
- 위치: 화면 상단 중앙 고정 (시스템 텍스트 팝업과 겹침 최소화)
- 외형: 배경 `#1C1C1E`(코너 8dp), 텍스트 흰색 14sp, 최대 너비 280dp, 형식 `「{변환미리보기}」 교체?`
- 터치 영역 방해 금지: `FLAG_NOT_FOCUSABLE` 적용, 탭 후 즉시 소멸 / 미탭 시 2초 후 자동 소멸

**⚠️ 핵심 구현체**: `HangulConverter.kt`
두벌식(QWERTY) ↔ 한국어 변환 로직. 상세 스펙은 `docs/features.md` 참조.

### Feature 5: 래디얼 메뉴 (배지 탭) — 사용자 원본 HTML 을 WebView 로 렌더

상시 배지를 **탭**(드래그 아님)하면 배지 주위로 살아 숨쉬는 유리 칩들이 부채꼴로 펼쳐지는
간편 메뉴를 띄운다. 빈 곳/항목 탭 시 닫힘.

- **외형·모션의 진실은 HTML 파일**: `app/src/main/assets/radialmenu.html`(= 사용자가 직접 제공한
  원본 `design/reference/radialmenu.html` 의 스냅샷에 앱 통합 배선을 더한 배포본 — 원본과 동일하지 않음). 과거 네이티브(Canvas) 재해석은 원본과 미세하게
  달라 폐기했고, 이제 `QuickMenuOverlayView` 가 이 HTML 을 **WebView 로 그대로 렌더**한다.
  ⚠️ 메뉴 외형을 바꾸려면 Kotlin 이 아니라 이 HTML 을 고친다.
- **원본에서 앱이 바꾼 것(사용자 요청)**: ① 선 위를 이동하던 빛 점(travel dot) 제거,
  ② 대신 선이 약하게 움직이도록(`#lineSway` 의 느린 translate) 변경, ③ 배지가 화면 좌측일 때
  팬 좌우 반전(`.menu.mirror`, 아래 "앵커 추종" 참조 — 앱 모드에서만 발동). 그 외(오브 morph·
  부유·별·먼지·버스트·색·치수)는 원본 그대로.
- **선의 움직임 방식(`applyLineBreath`)**: 배지/오브에 붙는 끝점은 고정한 채, 2차 베지어
  제어점만 선분에 수직으로 미세하게(±3.5px) 오가며 곡률(배)만 우아하게 출렁인다
  (d0↔d1↔d0↔d2↔d0, 선마다 5.5~6.5s 랜덤 주기 + 0.3s 스태거, `calcMode=spline`). glow/crisp
  두 겹은 항상 같은 `d` 시퀀스를 공유해 정렬이 어긋나지 않는다. reduce-motion 이면 정적 곡선.
- **앱 통합용 배선(보이는 디자인 불변)**: HTML 은 `window.KikiNative` 존재를 감지해 앱 모드로
  동작 — 자체 배지 숨김(네이티브 상시 배지와 중복 방지), 자동 오픈, `KikiInit({anchorX,anchorY(dp),
  reduceMotion, labels})` 로 배지 위치·라벨·저사양 반영. 오브 탭→`KikiNative.onItemTap(i)`,
  스크림 탭→`KikiNative.onDismiss()`, 배지 재탭→`KikiCollapse()`.
- **앵커 추종·화면 안 유지(KikiInit)**: 앵커(dp)는 네이티브가 `getLocationOnScreen` 기반
  배지 중심 화면 좌표로 전달(배지 창과 메뉴 창의 상태바 원점 차이 제거). HTML 쪽 클램프는
  스테이지 사각형(460×420 — 폰 뷰포트보다 넓어 앵커를 상수로 뭉갬)이 아니라 **팬 실측 범위**
  (오브+라벨+여백) 기준으로 최소 보정한다.
  방향 적응은 두 축 모두 지원한다(`evaluate(tilt)` 가 후보별로 **배지 이탈 거리 + 화면 잘림량×4**
  를 점수화해 최소를 고른다):
  - **좌우**: 배지가 화면 좌측이라 좌향 팬이 안 들어가면 `.menu.mirror`(CSS `scaleX(-1)`,
    라벨만 역반전)로 반전해 오른쪽으로 연다.
  - **상하(틸트)**: 배지가 최상/최하단이거나 가로 모드라 세로로 안 들어가면
    `computePositions(tilt)` 로 팬을 ∓35/∓70° 기울여(원본 각도식 + 상수) 아래/위로 연다.
    채택 시 `buildLines()` + `applyOrbPositions()` 로 선·오브 좌표만 다시 계산한다(오브 재생성
    없음 → 리스너·bob 파라미터 유지). 잘림량에 가중치를 두는 이유: 화면이 좁아 클램프가 축퇴하면
    "이탈 0"으로 보여 오브가 잘린 배치를 고르기 때문.
  배지가 중간 높이·우측이면 tilt=0 + 미러 없음 → **원본 지오메트리 그대로**.
- **재탭/빈 곳 탭으로 닫기**: 앱 모드에서 스테이지(`.menu`)는 `pointer-events:none`, 오브만
  `auto`. 스테이지 사각형이 네이티브 배지 위치까지 덮기 때문에, 이렇게 하지 않으면 배지를 다시
  눌러도 그 탭을 스테이지 배경이 삼켜 메뉴가 닫히지 않는다(탭은 아래 스크림으로 전달돼 닫힘).
- **로드 실패 안전망**: 이 창은 전체 화면을 덮고 터치를 소비하므로 열리지도 닫히지도 않으면
  화면이 잠긴다. `onReceivedError`/`onRenderProcessGone` + 로드 워치독(3s)으로 창을 제거하고,
  HTML 폴백 타이머도 기본 라벨로 여는 대신 `onDismiss` 로 닫는다(기본 라벨 순서는 네이티브 항목
  순서와 달라 "숨기기를 눌렀는데 앱이 열리는" 오동작이 된다).
- 항목(서비스가 주입): **앱 열기 / 설정 / 플래시 토글 / 한영타 토글 / 배지 숨기기**.
  토글은 탭 시점에 `Prefs` 를 읽어 현재 상태를 뒤집고 토스트로 새 상태(켜짐/꺼짐)를 안내.
- "저사양 모드(움직임 줄이기)" ON: 원본의 연속 애니메이션(오브 morph/부유/선 호흡) 정지 + 별/먼지
  **미생성** + 오브의 `backdrop-filter`/50px 글로우 제거(reduce 모드에서만 외형 단순화 — 풀모션 기기는
  원본 그대로). 사용자가 설정에서 명시하지 않았으면 **기기 자동 판정**(`Prefs.radialReduceMotion`:
  isLowRamDevice || 총 메모리 ≤ 4GiB || 시스템 애니메이션 배율 0) — Tab S6 Lite(4GB)는 기본 ON.
  HTML 은 `prefers-reduced-motion` 도 같은 모드로 취급.
- **WebView 재사용(저사양)**: 닫힌 메뉴 창의 WebView 를 `QUICK_MENU_CACHE_MS`(20s) 동안 보관했다가
  다음 오픈 때 `reopen()`(KikiInit 재호출)으로 즉시 연다 — 콜드 스타트(저사양 0.5~1.5s) 회피.
  만료·`onTrimMemory`·서비스 정리·렌더러 사망(`isDead`)·저사양 모드 값 변경 시 `destroyNow()`.
  JS 는 KikiInit 끝에 `KikiNative.onReady()` 로 ack 하며 워치독(로드 3s/재오픈 1.5s)은 이 ack 기준.
  HTML 은 재오픈 시 이전 펼침 상태를 접고 틸트를 `currentTilt` 와 비교해 달라질 때만 재배치.
- 배지가 사라지거나 서비스 정리 시 메뉴(WebView 창)도 함께 제거.
- 구현: `QuickMenuOverlayView`(WebView 호스트 + JS↔네이티브 브리지, 외부 의존성 없음) +
  `assets/radialmenu.html`(원본 렌더 대상).

### 추가 기능 2: 터치 키보드 제외

외장(블루투스 등) 키보드로 입력할 때만 kIkI 기능이 동작하도록, **화면 터치 키보드가 떠 있는 동안엔
플래시·배지·포커스 경고·한영타 교체를 전부 끄는** 옵션(설정, 기본 OFF).

- 판정 기준은 "외장 키보드 연결 여부"가 아니라 **"지금 화면 터치 키보드가 표시 중인가"**
  (`LangSenseAccessibilityService.computeSoftKeyboardVisible()`). 외장 키보드를 상시 연결해 두는
  사용자도 터치로 입력하는 순간엔 정확히 꺼지게 하기 위함.
- 터치 키보드 표시 판정은 **면적 기준**: 접근성 `TYPE_INPUT_METHOD` 창의 너비×높이가 화면 전체
  면적의 10%(`IME_KEYBOARD_MIN_SCREEN_AREA_FRACTION`) 이상이면 표시 중으로 본다. 외장 키보드의
  클립보드/추천 툴바(얇은 띠)와 실제 터치 키보드(플로팅/분리형 포함)를 면적으로 가른다.
- 외장 키보드 연결 자체는 `HardwareKeyboardDetector`(`InputManager`+`InputDevice`, 가상이 아닌
  알파벳 키보드만 인정)가 실시간 감지하며, `onConfigurationChanged` 가 도킹 등 일부 경로의 백스톱.
  이 감지기는 **옵션 ON 일 때만 생성**된다(OFF 면 그 결과가 하는 일이 없어 순수 오버헤드).
- 옵션이 꺼져 있으면(기본값) `windows` 순회를 하지 않을 뿐 아니라 `TYPE_WINDOWS_CHANGED` 구독과
  윈도우 추적 플래그 자체를 `syncServiceInfo()` 가 내려 이벤트가 앱에 도달조차 하지 않는다.
  ON 이면 `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOWS_CHANGED` 모두 150ms 디바운스 후 재평가.

---

## 권한 목록

```xml
<!-- 필수 (오버레이 표시) -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

`BIND_ACCESSIBILITY_SERVICE` 는 `uses-permission` 이 아니라 `<service android:permission=...>` 로 선언한다.

**포그라운드 서비스 권한은 선언하지 않는다**: 이 앱에는 `startForeground` 를 호출하는 서비스가 없다
(접근성 서비스는 시스템이 바인드해 유지). 특히 `FOREGROUND_SERVICE_SPECIAL_USE` 는 Play 심사에서
별도 정당화 사유 제출 의무가 있어 미사용 선언은 반려 위험. 배터리 최적화도
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`(제한 권한) 없이 시스템 설정 화면만 띄운다(`PermissionHelper`).

---

## 아키텍처 개요

```
LangSenseAccessibilityService (AccessibilityService)   # 클래스명은 식별자(불변)
  ├── ImeStateDetector         — IME 언어 전환 감지(Broadcast+ContentObserver+윈도우팝업,
  │                              권위 소스(설정값) 읽기 + 중복 합치기 + no-op 백오프 재확인)
  ├── KeyEventMonitor          — 포커스 없는 문자키 입력 카운트(게이트 후 지연 포커스 조회)
  └── TextSelectionMonitor     — 드래그 선택 + 한영타 판정

OverlayManager (WindowManager 래퍼)
  ├── FlashOverlayView         — 전체화면 플래시 (Feature 1, 3) / windowAnimations=0
  ├── BadgeOverlayView         — 상시 언어 배지 (Feature 2) / 크기·색 applyStyle / 탭→간편 메뉴
  ├── ReplaceChipView          — "교체?" 미니 버튼 (Feature 4)
  └── QuickMenuOverlayView     — 래디얼 메뉴 (Feature 5) / 사용자 원본 assets/radialmenu.html 을
                                 WebView 로 렌더 + JS↔네이티브 브리지(KikiInit/onItemTap/onDismiss)

util/
  ├── HangulConverter          — 두벌식↔QWERTY 변환 + 한영타 신뢰도 판정 (순수 Kotlin)
  ├── ImeLocaleParser          — locale 파싱 + One UI 팝업 패턴 매핑 (ja 분기는 주석 비활성화)
  ├── Prefs                    — SharedPreferences 래퍼(모든 설정 단일 진입점, 변경 즉시 적용)
  └── PermissionHelper         — 권한 안내 흐름

SettingsActivity
  └── 지원 언어 토글, 플래시 색/속도/횟수, 배지(ON·크기·배경색·글씨색),
      포커스 경고/한영타(설명 문구 포함) — 공용 colorPickerRow 로 색 UI 공유
      (온보딩/설정 모두 카드형 모던 UI: Theme.AppCompat.DayNight.NoActionBar 기반,
       values-night 다크모드 지원, SwitchCompat 토글 — 의존성 추가 없음)
```

---

## 구현 작업 순서 (Phase)

### Phase 1 — 뼈대 및 권한 흐름
- [ ] Gradle 프로젝트 초기화 (minSdk 29, targetSdk 35)
- [ ] `AndroidManifest.xml` 권한 및 서비스 선언
- [ ] 온보딩 화면: `SYSTEM_ALERT_WINDOW` 권한 → 접근성 서비스 활성화 안내
- [ ] `LangSenseAccessibilityService` 빈 뼈대 등록 확인

### Phase 2 — 핵심 감지 로직
- [ ] `ImeStateDetector`: `onAccessibilityEvent` TYPE_WINDOW_STATE_CHANGED 수신
- [ ] `InputMethodManager.getCurrentInputMethodSubtype()` locale 파싱
- [ ] Samsung One UI 시스템 팝업 텍스트 파싱 보조 로직 (기기별 fallback)
- [ ] 언어 상태 변경 이벤트 내부 브로드캐스트

### Phase 3 — 오버레이 UI
- [ ] `FlashOverlayView`: 전체화면 색상 오버레이 + 언어명 텍스트 + 150~250ms 애니메이션
- [ ] `BadgeOverlayView`: 드래그 가능한 상시 배지
- [ ] Feature 3 포커스 없는 키 입력 경고 오버레이

### Phase 4 — Feature 4 (한영타 교체)
- [ ] `HangulConverter`: 두벌식 QWERTY→한글 자모 매핑 테이블 + 음절 조합 알고리즘
- [ ] `HangulConverter.detectEnglishToKorean()`: 신뢰도 판정 로직
- [ ] `TextSelectionMonitor`: 드래그 선택 감지 + HangulConverter 호출
- [ ] `ReplaceChipView`: "교체?" 미니 버튼 오버레이 (2초 자동 소멸)
- [ ] `AccessibilityNodeInfo.performAction(ACTION_SET_TEXT)` 교체 실행

### Phase 5 — 설정 및 안정화
- [ ] `SettingsActivity`: 배지 위치, 기능별 ON/OFF
- [ ] API 29~35 각 버전별 동작 검증 코드 주석
- [ ] Samsung One UI 3.x / 5.x / 6.x / 7.x / 8.x 팝업 텍스트 패턴 매핑
- [ ] 배터리 최적화 예외 설정 유도 화면

---

## 주의 사항 및 알려진 제약

1. **Samsung One UI 팝업 텍스트**: One UI 버전마다 IME 전환 시스템 팝업의 텍스트가 다를 수 있음. `ImeStateDetector`에 버전별 패턴 매핑 테이블 유지 필요.
2. **Android 14 Foreground Service**: `foregroundServiceType` 미지정 시 크래시 발생. `specialUse` 타입으로 선언할 것.
3. **TYPE_APPLICATION_OVERLAY**: API 26+ 필수. API 29 이상만 지원하므로 별도 분기 불필요.
4. **ACTION_SET_TEXT 호환성**: 일부 앱(WebView 기반, 특수 에디터)에서 동작 안 할 수 있음. 클립보드 fallback 필수 구현.
5. **Accessibility 오용 방지**: 이 서비스는 키로깅, 화면 내용 수집, 외부 전송을 절대 수행하지 않음. 모든 처리는 로컬 온디바이스. `typeViewTextChanged` 도 구독하지만 텍스트 '내용'은 읽지 않고 `source.isEditable` 여부만 본다(포커스 경고 오발동 방지용). 디버그 로그는 언어 코드만 출력.
6. **One UI 9.x 미지원**: 베타 상태로 AccessibilityEvent 동작 변경 가능성 있음. 별도 분기 작성 금지, 추후 별도 대응.
7. **[일본어 비활성화]**: 일본어는 발음 입력 후 한자 변환 단계가 많아 한/영 감지 실효가 낮아 기능을 껐다. **삭제하지 않고 전부 주석 처리**(ImeLocaleParser 의 ja·日本語·日 감지, displayName/badgeLabel, Prefs 의 JA 분기/상수, 설정 체크박스·색상, strings/colors)하여 추후 재도입을 쉽게 함. 주석만 처리했으므로 일본어 IME 입력 시 전용 처리는 사라지고 **기타 언어와 동일한 일반 경로(회색 `#555555`, 라벨 "JA")** 로 흐른다(완전 억제는 아님).
8. **IME 전환 2회 깜박임 / 반응 씹힘 — 권위 소스 기반 방어(2026-07 재설계)**: 과거의 시간 가드
   다층 방어(불응기 450ms / 플랩 1000ms / 에피소드 700ms)는 stale `currentInputMethodSubtype`
   캐시 증상을 덮는 방식이라, 가끔 2회 깜박임이 남고 **정상 빠른 재전환(1초 내 한→영→한)까지
   차단해 "반응이 씹히는"** 부작용이 있었다. 현재 구조:
   - (a) **권위 소스 읽기**(근본 방어): 옵저버가 감시하는 설정값(`selected_input_method_subtype`
     해시 + IME id)을 직접 읽어 매핑하고, **관측 키가 실제로 바뀌었을 때만** 발동. 잔여 신호는
     "키 불변"으로 자연히 no-op → 시간 가드 없이 중복 차단, 빠른 정상 재전환은 그대로 발동.
   - (b) **신호 합치기**: `COALESCE_MS`(150ms) + 굶주림 방지 강제 평가(`COALESCE_MAX_WAIT_MS`=400ms).
     "변경 없음"이면 강한 신호에 한해 +200/+400ms 백오프 재확인 2회(늦은 설정 전파로 인한 유실 방지).
   - (c) **비권위 값 메아리 가드**(`ECHO_GUARD_MS`=1000ms): 팝업 힌트/IMM 폴백처럼 stale 가능한
     값이 "직전 언어"로 되돌아가는 경우만 무시. 권위 값에는 미적용(씹힘 방지). 팝업 힌트는 권위
     키가 그대로일 때만 발동 근거(삼성 내부 토글 대응 — 권위 키가 바뀌면 힌트는 stale 로 폐기).
   - (d) **`onServiceConnected` 멱등화 + 등록 개별 추적**: 재진입 시 `cleanup()` 후 재초기화.
     리시버/옵저버 등록 성공 여부를 개별 플래그로 추적하고 `stop()` 은 **무조건** 해제를 시도해,
     부분 등록 실패 시 리시버가 살아남아 detector 가 중복되던 누수를 차단.
   - (e) **렌더 단계 안전망**: 같은 색 중복 억제 창은 **직전 플래시의 실제 총 재생 길이
     (`FlashOverlayView.totalDurationMs` = N×1.5×dur + (N−1)×60ms) + 꼬리 여유 `FLASH_DEDUP_MS`(350ms)**
     로 동적 산정 — 과거의 시작 시각 기준 고정 350ms 는 짧은 설정(100ms×1회 = 총 150ms)에서 잔여
     중복(~350~550ms 후 도착)이 창 만료 직후 통과해 별개 2번째 깜박임으로 렌더되는 원인이었다.
     색 무관 에피소드 최소 간격은 `LANG_FLASH_MIN_INTERVAL_MS`(**300ms** — 과거 700ms 는 정상
     연속 전환 플래시까지 삼킴) 유지.
   - (f) **권위 역행 지연 확인(2026-07)**: 한 번의 토글에 서브타입 설정을 두 번 기록하는(중간값→
     최종값) IME 는 권위 키가 두 번 바뀌어 (a)의 키 가드를 통과한다(count=1 인데 2회 깜박임의 잔여
     원인). 발동 직후(`REVERT_CONFIRM_WINDOW_MS`=600ms) 직전 언어로 되돌아가는 권위 키 변경은
     즉시 발동하지 않고 `REVERT_CONFIRM_DELAY_MS`(250ms) 뒤 재확인 — 같은 키가 유지되면 진짜
     재전환으로 발동(차단이 아니라 최대 250ms 지연), 최종값으로 넘어갔으면 자연 no-op(중복 소멸).
     또한 팝업 텍스트가 현재 언어와 같으면(뉴스 없음) 강한 신호로 치지 않아, 후행 팝업이 재시도
     예산을 재충전해 메아리 가드 너머로 stale IMM 재발동을 끄는 연료를 차단.
   - (g) **약한 신호 배치의 IMM 폴백 금지 + 역행 창 확대(2026-07)**: 위 방어에도 "정상 재생이
     끝난 뒤 짧고 빠른 1회"가 남았는데, 원인은 **같은 색이 아니라 다른 색 오발동**이었다.
     `emitIfChanged` 가 같은 언어 재발동을 이미 막으므로 (e)의 동일 색 창은 발생 불가능한 경우를
     막고 있었고, 실제로는 ① 폴백 기기에서 `ECHO_GUARD_MS` 만료 후 **아무 앱의 윈도우 이벤트**
     (약한 신호)가 stale IMM 을 직전 언어로 재발동 → ② 곧이은 정정 발동이 `removeFlash()` 로 그
     플래시를 150~400ms 만에 절단 → "설정보다 짧은 부자연스러운 깜박임"으로 보였다. 그래서
     **IMM 폴백은 강한 신호가 포함된 재확인 체인에서만** 발동 근거로 인정한다(`pendingStrong`
     — 시간 가드가 아니라 신호 출처 게이트라 정상 재전환과 무관). 아울러 `REVERT_CONFIRM_WINDOW_MS`
     를 600→**1200ms** 로 넓혔다 — 자기 케이던스 상한(합치기 400 + 백오프 200+400 = 1000ms)보다
     짧아 늦게 관측된 바운스가 의심 판정을 그냥 통과했기 때문.
   `onLanguageChanged` 는 전환당 1회만 호출되도록 했고 `emitCount`+`Log.d` 로 실기기 검증 가능
   (렌더 안전망이 가려도 로그로 실제 트리거 수 확인 가능). 키 입력 시 전체 윈도우 순회는 게이트 통과 후로 지연.
9. **앱 표시 이름 = kIkI**: 사용자에게 보이는 문구만 kIkI. 패키지/applicationId(`com.langsense.app`), 클래스명(`LangSenseAccessibilityService`), 리소스 id(`Theme.LangSense`), Gradle `rootProject.name` 등 **식별자는 절대 변경 금지**.
10. **서비스 생존성(2026-08)**: 접근성 서비스에서 uncaught exception 은 프로세스 사망 → 시스템 재바인드
    의존(일부 ROM 은 접근성 토글을 꺼버림)이고, `onServiceConnected` 재초기화 중 예외는 `initialized=false`
    영구 고착 = "살아있지만 완전 무반응"(USB 연결 후 무반응 증상의 유력 원인). 그래서 **모든 시스템 콜백·
    핸들러 작업·백그라운드 평가는 `guarded{}` 로 감싸고**(감지 1회 유실 < 서비스 사망), `initialize()`
    실패는 2s 간격 최대 3회 자동 재시도, 키 평가 HandlerThread 는 uncaught 시 재생성(루퍼만 죽고 큐가
    살아 post 가 쌓이는 조용한 정지 방지), `ImeStateDetector` 리스너 등록 실패는 5s 후 재등록.
    특히 `hasActiveEditableFocus()` 는 백그라운드 스레드에서 돌므로 노드 접근/회수를 전부 보호한다.
11. **저사양 원칙(2026-08)**: ① 서비스 설정은 정적 XML 이 아니라 `syncServiceInfo()` 가 켜진 기능만큼만
    구독(키 필터·윈도우 추적·텍스트 변경은 해당 기능 ON 일 때만, notificationTimeout 100ms).
    ② 이벤트당 노드 IPC 최대 1회(선택 변경은 입력 실착 확인과 한영타 판정이 소스 노드를 공유, 소유권은
    `TextSelectionMonitor.onSelectionChanged(node): Boolean` 반환값으로 이전). ③ 한영타 판정은
    `HangulConverter.analyze()` 1회(정규식·이중 변환 없음), 전체 텍스트 복사는 칩을 띄울 때만.
    ④ 배지 스타일은 시그니처가 같으면 재적용 생략, 플래시 창은 재생 후 INVISIBLE 로 1.5s 유지해 연속
    전환 시 창 생성/파괴 생략, 메뉴 WebView 는 20s 유휴 캐시. ⑤ `ImeState.subtypeId` 처럼 읽는 곳 없는
    IPC 는 제거. ⑥ release 는 R8(`isMinifyEnabled=true`, Log.d 제거) — JS 브리지 keep 규칙 필수.
    ⑦ 래디얼 메뉴 저사양 모드는 기기 자동 판정(Feature 5 참조).

---

## 파일 구조 (목표)

```
langsense/
├── CLAUDE.md                  ← 이 파일
├── docs/
│   ├── features.md            ← 기능 상세 명세 + HangulConverter 스펙
│   └── architecture.md        ← 아키텍처 다이어그램
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/langsense/app/
│   │   │   ├── service/
│   │   │   │   ├── LangSenseAccessibilityService.kt
│   │   │   │   ├── ImeStateDetector.kt
│   │   │   │   ├── KeyEventMonitor.kt
│   │   │   │   └── TextSelectionMonitor.kt
│   │   │   ├── overlay/
│   │   │   │   ├── OverlayManager.kt
│   │   │   │   ├── FlashOverlayView.kt
│   │   │   │   ├── BadgeOverlayView.kt
│   │   │   │   └── ReplaceChipView.kt
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   └── SettingsActivity.kt
│   │   │   └── util/
│   │   │       ├── HangulConverter.kt    ← 두벌식↔QWERTY 변환 핵심 (순수 Kotlin)
│   │   │       ├── ImeLocaleParser.kt    ← locale/팝업 파싱 (ja 분기 주석 비활성화)
│   │   │       ├── Prefs.kt              ← 설정 단일 진입점(SharedPreferences)
│   │   │       └── PermissionHelper.kt
│   │   └── res/
│   │       ├── layout/
│   │       ├── values/
│   │       └── xml/
│   │           └── accessibility_service_config.xml
└── build.gradle.kts
```
