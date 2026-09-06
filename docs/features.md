# kIkI — 기능 상세 명세

> 앱 표시 이름은 **kIkI**. 패키지/클래스/리소스 id 등 식별자는 `com.langsense.app` / `LangSense…` 유지.
> 문서와 코드가 다르면 **코드가 진실**.

## Feature 1: 언어 전환 플래시

### 트리거 조건 (코드 기준: `ImeStateDetector`)
세 경로를 병행하여 한/영 전환을 즉시 감지한다.
1. **`BroadcastReceiver`** — `ACTION_INPUT_METHOD_CHANGED` (IME/서브타입 전체 전환)
2. **`ContentObserver`** — `Settings.Secure` 의 `selected_input_method_subtype` / `default_input_method`
   변경 (한/영 토글 시 시스템 설정이 즉시 바뀌므로 화면 변화 없이도 감지)
3. **`TYPE_WINDOW_STATE_CHANGED`** + Samsung One UI 팝업 텍스트 (백스톱 / 삼성 내부 토글)

현재 언어는 **권위 소스**에서 읽는다: 옵저버가 감시하는 설정값 그 자체
(`selected_input_method_subtype` 해시 + `default_input_method`)를 직접 읽어 해당 IME 의 활성
서브타입 목록과 매핑(`readAuthoritative`, IME id 기준 목록 캐시). 신호가 도착한 시점엔 설정값이
이미 갱신돼 있어 **stale 이 없다**. 매핑 불가 기기만 `imm.currentInputMethodSubtype` 폴백(비권위).

### ★ 2회 깜박임 / 반응 씹힘 방지 — 권위 소스 기반 방어(2026-07 재설계)
과거의 시간 가드 다층 방어(불응기 450ms/플랩 1000ms/에피소드 700ms)는 stale 캐시 증상을 덮는
방식이라 가끔 2회 깜박임이 남고, 1초 내 정상 재전환(한→영→한)까지 차단해 반응이 씹혔다. 현재:
1. **권위 소스 읽기(근본 방어)**: 권위 값은 **관측 키("IME id:서브타입 해시")가 실제로 바뀌었을
   때만** 발동 근거(`lastAuthKey`). 전환의 잔여 신호는 "키 불변"으로 자연히 no-op → 시간 가드 없이
   중복 차단, 빠른 정상 재전환은 그대로 발동(씹힘 해소).
2. **신호 합치기(coalesce)**: 모든 신호를 단일 예약(`recheckRunnable`)으로 합쳐 신호가 멎고
   `COALESCE_MS`(150ms) 뒤 한 번만 평가. 신호가 끝없이 이어져도 첫 신호 후
   `COALESCE_MAX_WAIT_MS`(400ms)에 강제 평가(굶주림 방지). 평가가 "변경 없음"이면 강한 신호
   (브로드캐스트/옵저버/팝업)에 한해 +200/+400ms 백오프로 최대 2회 재확인 — 저사양 기기의 늦은
   설정 전파로 전환이 조용히 유실되는 것을 방지.
3. **비권위 값 메아리 가드(`ECHO_GUARD_MS`=1000ms)**: 팝업 힌트/IMM 폴백처럼 stale 가능한 값이
   "직전 언어"로 되돌아가는 경우만 무시. 권위 값에는 미적용(씹힘 방지). 팝업 힌트는 권위 키가
   그대로일 때만 발동 근거(삼성 내부 토글 — 권위 키가 바뀌면 힌트는 stale 로 폐기).
4. **`onServiceConnected` 멱등화 + 등록 개별 추적**: 재진입 시 `cleanup()` 후 재초기화.
   리시버/옵저버 등록 성공을 개별 플래그로 추적하고 `stop()` 은 무조건 해제를 시도 — 부분 등록
   실패 시 리시버가 살아남아 detector 가 중복되던 누수 차단.
5. **렌더 단계 안전망**: 같은 색 `FLASH_DEDUP_MS`(350ms) + 색 무관 에피소드 최소 간격
   `LANG_FLASH_MIN_INTERVAL_MS`(300ms — 과거 700ms 는 정상 연속 전환 플래시까지 삼킴).

따라서 깜박임 횟수 1 지정 시 **정확히 1회**만 깜박이고, 빠른 정상 재전환도 씹히지 않는다.
전환당 `onLanguageChanged` 호출이 1회임은 `emitCount` + `Log.d` 로 실기기 확인 가능
(렌더 안전망이 시각적으로 가려도 로그로 실제 트리거 수는 확인 가능).

### Samsung One UI 팝업 보조 감지 (fallback)
경로 3에서 시스템 팝업 텍스트로 보조 감지(팝업 힌트는 서브타입보다 우선 — 삼성 내부 토글 대응).

| One UI 버전 | 팝업 텍스트 패턴 (한국어 전환) | 팝업 텍스트 패턴 (영어 전환) |
|---|---|---|
| One UI 3.x | `"한국어"` | `"English"` |
| One UI 5.x | `"한국어"` | `"English (US)"` |
| One UI 6.x | `"한국어"` 또는 `"Korean"` | `"English"` |
| One UI 7.x | `"한국어"` | `"English"` |
| One UI 8.x | 실기기 로그로 확인 필요 (TBD) |
| One UI 9.x | **미지원 (베타)** |

패키지명 필터: `packageName == "android"` 또는 `packageName.contains("samsung")` 인 이벤트만 처리

### 오버레이 스펙
```
WindowManager.LayoutParams:
  type    = TYPE_APPLICATION_OVERLAY
  flags   = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN
  width   = MATCH_PARENT
  height  = MATCH_PARENT
  format  = PixelFormat.TRANSLUCENT

애니메이션:
  windowAnimations = 0   # ★ 윈도우 enter/exit 애니메이션 제거
                         #   → 색이 좌→우로 채워지지 않고 전체 화면이 한 프레임에 꽉 차게 나타남
  나타남: 즉시 (윈도우 애니메이션 없음)
  사라짐: 뷰 알파 페이드아웃 (1회 지속시간 = flash_duration_ms, 기본 200ms)
  깜박임 횟수: flash_count (기본 1, 1~5)
```

### 언어별 색상 코드 (기본값; 설정에서 #RRGGBB 로 변경 가능, ALPHA 0.85 적용)
- 한국어(`ko`) → `#CC2D2D`
- English(`en`) → `#1A6EBD`
- 기타 → `#555555`
- ~~日本語(`ja`) → `#2D8C4E`~~ — **[일본어 비활성화]** 코드 주석 처리(`Prefs.DEFAULT_JA` 상수는 보존)

---

## Feature 2: 상시 언어 배지

### 표시 스펙 (코드 기준: `BadgeOverlayView.applyStyle`)
```
크기 3단계 (badge_size, 기본 1=중):
  소(0): 12sp / 패딩 6·3dp / minWidth 32dp
  중(1): 14sp / 패딩 8·4dp / minWidth 40dp   ← 기본 = 기존 외형
  대(2): 18sp / 패딩 11·6dp / minWidth 52dp
배경: GradientDrawable, 코너 6dp, 색 = badge_bg_color(#RRGGBB) + BADGE_BG_ALPHA(0.8)
      → 기본값(#000000)이면 기존 #CC000000 과 100% 동일
텍스트: Bold, 색 = badge_text_color(#RRGGBB, 불투명), 기본 흰색
  한국어 → "한"
  English → "EN"
  기타 → locale 앞 2자리 대문자
  ~~日本語 → "日"~~  [일본어 비활성화]

위치 저장: SharedPreferences ("badge_x", "badge_y")
기본 위치: 우하단 (margin 16dp)
색 선택 UI: 플래시 색상과 동일한 공용 컴포넌트(32색 팔레트 + #RRGGBB 입력) 재사용
설정 변경 시: applyStyle 재호출로 즉시 반영
```

### 드래그 동작
- `OnTouchListener`로 ACTION_MOVE 감지
- 화면 경계 벗어나지 않도록 clamp 처리
- 드래그 종료 시 위치 자동 저장

---

## Feature 3: 포커스 없는 키 입력 경고

입력칸을 선택하지 않은 채 키보드를 치면 글자가 어디에도 안 들어간다. 이때만 경고를 띄운다.

### 감지 로직 (코드 기준: `KeyEventMonitor` + Service)
`onKeyEvent` 는 **항상 false**(이벤트 미소비). 다음 순서로 평가한다(앞 단계에서 걸러지면 비싼 조회 생략):
1. 기능 ON + `ACTION_DOWN` + 비반복 + **실제 문자 키**(modifier/단축키/기능키 제외) 게이트.
2. **입력 실착 확인(최우선·오발동 방지)**: 최근 `RECENT_INPUT_MS`(1.2s) 안에 편집 노드의
   `typeViewTextChanged`/`typeViewTextSelectionChanged` 가 있었으면(=글자가 실제 입력됨) 포커스
   있음으로 간주하고 카운터 초기화. 포커스가 정말 없으면 이런 이벤트가 없으므로 진짜 경고는 유지.
3. 포커스 조회(지연 평가): 1차 활성 윈도우 `findFocus(FOCUS_INPUT)?.isEditable`, 없을 때만
   2차 전체 윈도우 순회(일시적 null 방어).
4. 그래도 포커스 없으면 카운터 증가. 임계값(기본 3) 도달 시 **바로 경고하지 않고** 아래 지연 검증을 거쳐
   발동, 재경고 쿨다운 `WARN_COOLDOWN_MS`(2.5s).
5. **발동 전 지연 검증(`WARN_VERIFY_DELAY_MS`=400ms)**: 저사양 기기는 글자가 실제 입력돼도 그 확인
   이벤트(text/selection 변경)와 포커스 노드 갱신이 수백 ms 늦게 도착한다. 임계값 도달 즉시 경고하면
   그 지연 확인이 오기 전에 오경고가 뜨므로, 이 시간만큼 미뤘다가 재확인한다. 그 사이 입력 실착이
   확인되거나(`lastEditableActivityAt` 재조회) 포커스가 잡히면 경고를 **취소**, 진짜 포커스가 없으면
   확인 이벤트가 오지 않아 정상 발동. → "입력은 되는데 저사양에서 가끔 경고가 뜨던" 잔여 오발동 해결.

> 텍스트 '내용'은 절대 읽지 않는다(`source.isEditable` 여부만 확인). 카운터는 포커스 획득/입력 실착 시 초기화.
> 지연 검증은 키 평가(백그라운드) 스레드에서 예약되므로 키 디스패치를 막지 않는다.

---

## Feature 4: 한영타 감지 + "교체?" 인라인 버튼

### 개요
영어 자판 상태에서 한국어를 쳐서 생긴 "한영타"(예: `dlfjgrp` → `안드로`)를
드래그 선택하면 상단에 **"교체?"** 버튼을 띄워 탭 한 번에 올바른 한국어로 교체.

### 핵심 유틸: HangulConverter.kt

#### 두벌식(QWERTY → 한국어 자모) 매핑 테이블

```kotlin
object HangulConverter {

    // 소문자 → 한국어 자모 (두벌식 기준)
    private val ENG_TO_JAMO = mapOf(
        'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ',
        'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ', 'p' to 'ㅔ',
        'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ',
        'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ', 'l' to 'ㅣ',
        'z' to 'ㅋ', 'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ',
        'b' to 'ㅠ', 'n' to 'ㅜ', 'm' to 'ㅡ'
    )

    // 대문자 → 쌍자음/쌍모음
    private val ENG_UPPER_TO_JAMO = mapOf(
        'Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ',
        'O' to 'ㅒ', 'P' to 'ㅖ'
    )

    // 초성 인덱스 (19개)
    private val CHOSUNG = listOf(
        'ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄸ','ㄹ',
        'ㄺ','ㄻ','ㄼ','ㄽ','ㄾ','ㄿ','ㅀ','ㅁ','ㅂ','ㅃ',
        'ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
    )

    // 중성 인덱스 (21개)
    private val JUNGSUNG = listOf(
        'ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ','ㅙ','ㅚ',
        'ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ'
    )

    // 종성 인덱스 (28개, 0 = 없음)
    private val JONGSUNG = listOf(
        '\u0000','ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ','ㄻ','ㄼ',
        'ㄽ','ㄾ','ㄿ','ㅀ','ㅁ','ㅂ','ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
    )

    /**
     * 신뢰도 판정: 입력 문자열이 한영타일 가능성 반환 (0.0 ~ 1.0)  [실제 구현 기준]
     *
     * 두 신호를 곱한다:
     *  - mapRatio   = 두벌식 자판에 매핑되는 영문자 비율
     *  - composeRatio = 변환 결과 중 "완성형 한글 음절" / (완성형 + 조합 실패해 남은 낱자모)
     * 실제 영어 단어는 모음/자음 배열이 한글 규칙과 어긋나 낱자모가 많이 남아 낮게 나온다.
     * (설정의 "신뢰도 임계값(%)" 인라인 설명이 이 로직을 사용자 언어로 풀어 쓴 것)
     */
    fun detectEnglishToKorean(input: String): Float {
        val letters = input.filter { it.isLetter() }
        if (letters.isEmpty()) return 0f
        val mappable = letters.count { engToJamo(it) != null }
        if (mappable == 0) return 0f
        val mapRatio = mappable.toFloat() / letters.length

        val converted = convertEngToKor(input)
        var syllables = 0; var looseJamo = 0
        for (ch in converted) when (ch.code) {
            in 0xAC00..0xD7A3 -> syllables++          // 완성형 음절
            in 0x3130..0x318F -> looseJamo++          // 남은 낱자모(호환 자모)
        }
        val units = syllables + looseJamo
        if (units == 0) return 0f
        return (syllables.toFloat() / units) * mapRatio
    }

    /**
     * 영타 → 한글 변환 (두벌식 조합 알고리즘)
     * 공백, 숫자, 특수문자는 그대로 보존
     */
    fun convertEngToKor(input: String): String {
        val jamos = input.map { ch ->
            ENG_TO_JAMO[ch] ?: ENG_UPPER_TO_JAMO[ch] ?: ch
        }
        return assembleJamos(jamos)
    }

    /**
     * 자모 리스트를 완성형 한글로 조합
     * 한글 유니코드: 0xAC00 + (초성idx * 21 + 중성idx) * 28 + 종성idx
     */
    private fun assembleJamos(jamos: List<Char>): String {
        // 두벌식 조합 알고리즘 구현
        // 초성 + 중성 → 음절, 이후 자음 오면 종성 처리
        // 상세 구현은 표준 두벌식 오토마타 참고
        val sb = StringBuilder()
        var i = 0
        while (i < jamos.size) {
            val ch = jamos[i]
            val chIdx = CHOSUNG.indexOf(ch)
            if (chIdx >= 0 && i + 1 < jamos.size) {
                val next = jamos[i + 1]
                val jungIdx = JUNGSUNG.indexOf(next)
                if (jungIdx >= 0) {
                    // 초성 + 중성 조합
                    val syllable = 0xAC00 + (chIdx * 21 + jungIdx) * 28
                    // 종성 확인
                    if (i + 2 < jamos.size) {
                        val jongCh = jamos[i + 2]
                        val jongIdx = JONGSUNG.indexOf(jongCh)
                        if (jongIdx > 0 && (i + 3 >= jamos.size || JUNGSUNG.indexOf(jamos[i + 3]) < 0)) {
                            sb.append((syllable + jongIdx).toChar())
                            i += 3
                            continue
                        }
                    }
                    sb.append(syllable.toChar())
                    i += 2
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }
}
```

> ℹ️ 위 블록은 **개념 골격**이다. 실제 `HangulConverter.kt` 는 표준 두벌식 오토마타를 완전히 구현했다
> (복합 중성 `ㅘ/ㅚ/ㅢ`, 복합 종성 `ㄺ/ㄼ/ㅄ`, 연음·도깨비불, 역변환 `convertKorToEng` 포함).
> 안드로이드 의존성이 없는 순수 Kotlin 이라 JVM 단위 테스트로 검증된다(`HangulConverterTest`).

### TextSelectionMonitor 감지 로직

```kotlin
// TYPE_VIEW_TEXT_SELECTION_CHANGED 이벤트 처리
fun onTextSelectionChanged(event: AccessibilityEvent) {
    val node = event.source ?: return
    val text = node.text?.toString() ?: return
    val selStart = node.textSelectionStart
    val selEnd = node.textSelectionEnd

    // 유효한 선택 범위인지 확인
    if (selStart < 0 || selEnd <= selStart) return
    val selectedText = text.substring(selStart, selEnd)
    if (selectedText.isBlank() || selectedText.length < 2) return

    // 한영타 신뢰도 판정
    val confidence = HangulConverter.detectEnglishToKorean(selectedText)
    if (confidence >= 0.70f) {
        val converted = HangulConverter.convertEngToKor(selectedText)
        overlayManager.showReplaceChip(
            originalNode = node,
            original = selectedText,
            converted = converted,
            selStart = selStart,
            selEnd = selEnd
        )
    }
}
```

### ReplaceChipView 스펙

```
위치: 화면 상단 고정 (status bar 바로 아래, 중앙 정렬)
크기: wrap_content × 32dp (최대 너비 280dp)
내용: "「{변환 미리보기}」 교체?"
  예: 「안드로이드」 교체?
배경: #1C1C1E (다크), 코너 반경 8dp
텍스트: 14sp, 흰색

WindowManager flags:
  TYPE_APPLICATION_OVERLAY
  FLAG_NOT_FOCUSABLE (터치 입력 방해 금지)
  단, 칩 본체는 클릭 가능해야 하므로 칩 외부만 FLAG_NOT_TOUCHABLE

자동 소멸: 2000ms 후 fadeOut + 제거
탭 시:
  1. AccessibilityNodeInfo.performAction(
       AccessibilityNodeInfo.ACTION_SET_TEXT,
       Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newFullText) }
     )
  2. 실패 시: 클립보드 복사 + Paste 액션 실행
  3. ReplaceChipView 즉시 소멸
```

---

## Feature 5: 래디얼 메뉴 (배지 탭) — 사용자 원본 HTML 을 WebView 로 렌더

상시 배지를 **탭**하면(드래그와 구분) 배지 주위로 살아 숨쉬는 유리 칩이 부채꼴로 펼쳐지는 간편 메뉴.
**외형·모션의 진실은 HTML 파일**: `app/src/main/assets/radialmenu.html`(사용자 원본
`design/reference/radialmenu.html` 에 앱 통합 배선을 더한 배포본 — 원본 스냅샷과 동일하지 않음). 과거 네이티브(Canvas) 재이식은 원본과 미세하게 달라
폐기했고, 이제 `QuickMenuOverlayView` 가 이 HTML 을 **WebView 로 그대로 렌더**한다(메뉴 외형을
바꾸려면 Kotlin 이 아니라 HTML 을 고친다).

### 구성
- `QuickMenuOverlayView`: 투명 배경 WebView 호스트. `file:///android_asset/radialmenu.html` 로드 +
  JS↔네이티브 브리지(`KikiNative`). 외부 의존성 없음(android.webkit).
- `assets/radialmenu.html`: 렌더 대상. 오브(유리 칩 80×56dp, 코너 morph)·곡선 선·별·먼지·부유·
  버스트 등 모든 연출은 이 파일 안에 있다.
- **원본에서 앱이 바꾼 것은 단 두 가지(사용자 요청)**: ① 선 위 빛 점(travel dot) 제거,
  ② 대신 선이 약하게 움직이도록(`#lineSway` translate sway) 변경. 그 외는 원본 그대로.
- **선의 움직임(`applyLineBreath`)**: 끝점(배지/오브)은 고정, 2차 베지어 제어점만 선분에
  수직으로 ±3.5px 오가며 곡률만 우아하게 출렁인다(선마다 5.5~6.5s 랜덤 + 0.3s 스태거,
  glow/crisp 동일 `d` 시퀀스 공유). reduce-motion 이면 정적.
- **앱 통합용 배선(디자인 불변)**: `window.KikiNative` 감지 시 앱 모드 — 자체 배지 숨김(네이티브
  상시 배지와 중복 방지), 자동 오픈, `KikiInit({anchorX,anchorY(dp), reduceMotion, labels})` 로
  배지 위치·라벨·저사양 반영. 오브 탭→`onItemTap(i)`, 스크림 탭→`onDismiss()`, 배지 재탭→`KikiCollapse()`.
- 윈도우: `TYPE_APPLICATION_OVERLAY | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`(터치는 받되 키
  포커스 안 가져감), `windowAnimations=0`. 스크림 딤은 HTML 내부(.scrim rgba(0,0,0,0.25)).
- "저사양 모드" ON: 원본의 연속 애니메이션(오브 morph/부유/별/먼지/선 sway) 정지.
- 닫힘: 빈 곳(스크림) 탭 또는 항목 탭. 배지가 사라지거나 서비스 정리 시 WebView 창도 함께 제거.

### 항목 (서비스가 주입: `OverlayManager.setQuickMenuItems`)
| 라벨 | 동작 |
|---|---|
| 앱 | `MainActivity` 실행(`FLAG_ACTIVITY_NEW_TASK`) |
| 설정 | `SettingsActivity` 실행 |
| 플래시 | `Prefs.flashEnabled` 토글 + 토스트(켜짐/꺼짐) |
| 한영타 | `Prefs.replaceEnabled` 토글 + 토스트 |
| 숨기기 | `Prefs.badgeEnabled=false` (배지 숨김; 설정에서 재활성) |

> 토글 동작은 **탭 시점에** `Prefs` 를 읽어 현재 값을 뒤집으므로 항상 최신 상태로 동작한다.

---

## 추가 기능 2: 터치 키보드 제외

블루투스 등 외장 키보드로 입력할 때만 kIkI 기능(플래시/배지/포커스 경고/한영타 교체)이 동작하도록,
**화면 터치 키보드가 떠 있는 동안엔 전부 끄는** 옵션(`SettingsActivity` → "터치 키보드 제외", 기본 OFF).

### 판정 로직
- **외장 키보드 연결 감지(`HardwareKeyboardDetector`)**: `InputManager`+`InputDevice` 로 "가상이 아닌
  알파벳(QWERTY) 키보드"(`SOURCE_KEYBOARD` 비트 포함)가 있으면 연결로 판정. `InputDeviceListener` 로
  실시간 연결/해제 통지(서비스의 `onConfigurationChanged` 가 도킹 등 일부 경로의 백스톱).
- **활성/비활성 기준은 "연결 여부"가 아니라 "화면 터치 키보드 표시 여부"**
  (`LangSenseAccessibilityService.computeSoftKeyboardVisible()`). 외장 키보드를 상시 연결해 두는
  사용자는 연결 기준이면 터치 입력 시에도 계속 켜져 있어 옵션이 무의미해지기 때문에, "지금 어느
  키보드로 입력 중인가"를 직접 반영하는 표시 여부를 쓴다.
- **터치 키보드 표시 판정(면적 기준)**: 접근성 `windows` 중 `TYPE_INPUT_METHOD` 창의 **너비×높이가
  화면 전체 면적의 `IME_KEYBOARD_MIN_SCREEN_AREA_FRACTION`(10%) 이상**이면 "표시 중"으로 본다.
  외장 키보드의 클립보드/추천 툴바(전체 폭이지만 얇은 띠, 면적 비율 한 자릿수 %)와 실제 터치
  키보드(플로팅/분리형처럼 작아도 가로·세로 모두 상당 부분 차지, 면적 비율 15%+)를 가른다.
  (이전엔 높이만 봤으나 세로 폭이 좁은 플로팅/분리형 키보드를 "없음"으로 오판할 수 있어 면적 기준으로 전환.)
- **적용 범위**: `featuresEnabled() = !excludeTouchKeyboard || !softKeyboardVisible` 를 언어 전환
  플래시/배지, 포커스 없는 키 입력 경고, 한영타 교체 세 곳 모두에서 게이트로 사용.

### 저사양 최적화
옵션이 꺼져 있으면(기본값) `windows` 순회 자체를 하지 않아 일반 사용자에겐 부하가 없다.

---

## ImeLocaleParser 유틸리티

```kotlin
object ImeLocaleParser {

    fun parseLocale(subtype: InputMethodSubtype?): String {
        subtype ?: return "unknown"
        // minSdk 29 이므로 languageTag(API 24+)는 항상 사용 가능. 빈 값일 때만 deprecated 한
        // subtype.locale 로 폴백(그 참조 때문에 @Suppress("DEPRECATION") 유지).
        val locale = subtype.languageTag.ifEmpty { subtype.locale }
        return when {
            locale.startsWith("ko") -> "ko"
            // [일본어 비활성화] locale.startsWith("ja") -> "ja"  (주석 보존)
            locale.startsWith("en") -> "en"
            locale.startsWith("zh") -> "zh"
            else -> locale.take(2).lowercase()   // 일본어 IME 는 여기서 "ja"(기타 취급)로 흐름
        }
    }

    // Samsung One UI 시스템 팝업 텍스트 → 언어 추론 (fallback)
    fun parseFromSystemPopupText(text: String): String? {
        return when {
            text.contains("한국어") || text.contains("Korean", ignoreCase = true) -> "ko"
            text.contains("English", ignoreCase = true) -> "en"
            // [일본어 비활성화] text.contains("日本語")/Japanese -> "ja"  (주석 보존)
            text.contains("中文") || text.contains("Chinese", ignoreCase = true) -> "zh"
            else -> null
        }
    }

    // displayName/badgeLabel 의 JA 분기("日本語"/"日")도 동일하게 주석 비활성화.
}
```

---

## 설정 항목 (SettingsActivity)

| 항목 | 기본값 | 타입 / 범위 | 비고 |
|---|---|---|---|
| 지원 언어 토글(한국어/영어) | ON | Boolean | 일본어 항목은 [비활성화]로 주석 |
| 전환 플래시 활성화 | ON | Boolean | |
| 깜박임 속도(지속시간) | 200ms | Int 100~500 (step 50) | |
| 깜박임 횟수 | 1회 | Int 1~5 | coalesce 로 1회 지정 시 1회만 |
| 언어별 플래시 색상 | 코드별 기본값 | #RRGGBB | 공용 색 선택 컴포넌트 |
| 상시 배지 표시 | ON | Boolean | |
| **배지 크기** | 중 | 소/중/대 (라디오) | 신규 [5] |
| **배지 배경색** | #000000(+α0.8) | #RRGGBB | 신규 [5], 공용 컴포넌트 |
| **배지 글씨색** | #FFFFFF | #RRGGBB | 신규 [5], 공용 컴포넌트 |
| 배지 위치 X/Y | 우하단 | Int (px) | 드래그로 변경·저장 |
| 포커스 없음 경고 | ON | Boolean | **인라인 설명 추가 [3]** |
| 포커스 없음 임계값 | 3회 | Int 1~5 | |
| 한영타 교체 기능 | ON | Boolean | |
| 한영타 신뢰도 임계값 | 70% | Int 50~90 (step 5) | **인라인 설명 추가 [4]** |

> 색 선택 UI(32색 팔레트 + #RRGGBB 입력)는 `colorPickerRow` 공용 컴포넌트로 추출되어
> 플래시 색 설정과 배지 색 설정이 동일 코드를 공유한다(복붙 방지).
