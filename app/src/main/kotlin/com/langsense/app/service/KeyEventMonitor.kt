package com.langsense.app.service

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * 포커스 없는 키 입력 경고 (Feature 3).
 *
 * 입력 포커스(편집 가능한 노드)가 없는 상태에서 "문자 입력 키"가 연속 [thresholdProvider] 회
 * 이상 눌리면 [onWarn] 을 호출한다.
 *
 * ### 스레드 모델 (Bug 1 — 입력 끊김/문자 몰림 근본 수정)
 * 접근성 서비스의 `onKeyEvent` 는 **메인(디스패치) 스레드**에서 호출되고, 시스템은 이 콜백이
 * 반환될 때까지 다음 키 디스패치를 기다린다(키 이벤트 필터링). 따라서 콜백 안에서 접근성 노드
 * 트리를 IPC 로 훑는 **무거운 포커스 조회**(`rootInActiveWindow.findFocus`, 전체 `windows` 순회)를
 * 동기로 수행하면 키마다 수십 ms 가 쌓여, 블루투스 키보드로 빠르게 칠 때 "1~2초 멈췄다가
 * 버퍼된 글자가 한꺼번에 쏟아지는" 증상이 생긴다.
 *
 * 그래서 처리를 둘로 나눈다.
 *  - [isTypingCandidate] : 키 이벤트 객체의 속성만 보는 **순수·저비용** 판정(노드 접근/IPC 없음).
 *    디스패치 스레드에서 동기로 호출하고 즉시 결과를 받아 `onKeyEvent` 가 곧바로 반환되게 한다.
 *  - [handleCandidate]   : 포커스 조회·카운터·경고 등 **무거운 로직**. 서비스가 백그라운드 스레드로
 *    넘겨 호출하므로 키 디스패치를 절대 막지 않는다.
 *
 * 포커스 조회 콜백들은 생성자에서 한 번 주입한다 — 과거처럼 키마다 람다 4개를 새로 만들어 넘기면
 * 키당 객체 6개가 할당됐다(저사양 GC 압박).
 *
 * 카운터/타임스탬프 상태는 오직 [handleCandidate] 안에서만 변경되며, 서비스가 이를 단일 백그라운드
 * 스레드에서만 호출하므로 별도 동기화 없이 안전하다.
 *
 * ### 오발동 방지 (오경고)
 * 블루투스 키보드로 정상 타이핑 중에도 `findFocus(FOCUS_INPUT)` 가 순간적으로 null 을
 * 반환할 때가 있어(렌더 타이밍), 이를 그대로 믿으면 "선택되지 않음" 경고가 잘못 뜬다. 다음으로 방어한다:
 *  - **입력 실착(landing) 확인이 최우선**: 글자가 실제 입력칸에 들어가면 그 편집 노드가
 *    text/selection 변경 이벤트를 낸다(recentEditableActivity). 최근에 그런 활동이 있었다면
 *    포커스 조회 결과와 무관하게 "포커스 있음"으로 간주한다.
 *  - **단축키 제외**: Ctrl/Alt/Meta 조합은 타이핑이 아니므로 무시.
 *  - **재확인(grace)**: [focusProbe] 는 1차(활성 윈도우) 실패 시 2차(전체 윈도우)까지 결합한 조회다.
 *    한 번이라도 편집 포커스가 잡히면 카운터를 즉시 초기화.
 *  - **발동 전 지연 검증(저사양 오경고 방지)**: 임계값에 닿아도 바로 띄우지 않고 [scheduleVerify] 로
 *    잠깐 미뤘다가, 그 사이 편집 활동이 확인되거나([recheckRecentEditableActivity]) 포커스가
 *    잡히면([verifyProbe] — 캐시를 거치지 않는 신선한 조회) 경고를 취소한다.
 *  - **경고 쿨다운**: 경고를 띄운 직후 [WARN_COOLDOWN_MS] 동안은 다시 띄우지 않아 도배 방지.
 *
 * onKeyEvent 는 절대 이벤트를 소비하지 않는다(항상 false).
 */
class KeyEventMonitor(
    private val enabledProvider: () -> Boolean,
    private val thresholdProvider: () -> Int,
    /** 편집 포커스 조회(1차+2차 결합). 키마다 호출되므로 서비스가 짧게 캐시해도 된다. */
    private val focusProbe: () -> Boolean,
    /** 지연 검증용 포커스 조회 — 캐시를 거치지 않는 신선한 값(캐시가 오경고를 고정하지 않게). */
    private val verifyProbe: () -> Boolean,
    /** 넘긴 작업을 평가 스레드에서 [WARN_VERIFY_DELAY_MS] 뒤에 실행하도록 예약. */
    private val scheduleVerify: (action: () -> Unit) -> Unit,
    /** 지연 검증 시점에 편집 활동을 **새로** 다시 읽는 콜백(저사양의 늦은 확인 이벤트 반영). */
    private val recheckRecentEditableActivity: () -> Boolean,
    private val onWarn: () -> Unit
) {
    private var noFocusKeyCount = 0
    private var lastWarnAt = 0L
    /** 지연 검증이 예약되어 대기 중인지. 대기 중이면 추가 임계값 도달 시 중복 예약하지 않는다. */
    private var verifyPending = false

    /** 지연 검증 작업(1회 생성해 재사용 — 임계값 도달마다 람다를 만들지 않는다). */
    private val verifyAction: () -> Unit = {
        verifyPending = false
        if (!(recheckRecentEditableActivity() || verifyProbe())) {
            onWarn()
            lastWarnAt = SystemClock.uptimeMillis()
        }
    }

    /**
     * 디스패치 스레드에서 동기로 호출하는 **저비용** 1차 게이트.
     * 키 이벤트 객체의 속성만 보고(노드 접근/IPC 없음) 이 키가 무거운 평가([handleCandidate]) 대상인지
     * 판정한다. 모디파이어/반복/단축키/비문자 키는 여기서 걸러 백그라운드 작업조차 만들지 않는다.
     *
     * @return 백그라운드에서 포커스 평가가 필요한 "실제 문자 입력 키 누름"이면 true.
     */
    fun isTypingCandidate(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return false // 길게 눌러 반복되는 이벤트는 1회로만 취급
        return isTypingKey(event)
    }

    /**
     * 무거운 평가(백그라운드 스레드에서 호출). 포커스 조회/카운터/경고를 모두 여기서 처리한다.
     *
     * @param recentEditableActivity 최근에 편집칸의 text/selection 변경이 있었는지(=글자가 실제 입력됨).
     *        디스패치 스레드에서 스냅샷한 값을 넘겨받는다(저비용).
     */
    fun handleCandidate(recentEditableActivity: Boolean) {
        if (!enabledProvider()) {
            noFocusKeyCount = 0
            return
        }
        // 1순위: 최근에 입력칸이 실제로 글자를 받았다면(편집 이벤트 발생) 포커스 있음으로 간주.
        // 비용도 가장 싸고(이미 계산된 불리언) 오경고를 근본적으로 막는다. 포커스 조회보다 먼저 본다.
        if (recentEditableActivity) {
            noFocusKeyCount = 0
            return
        }

        if (focusProbe()) {
            noFocusKeyCount = 0
            return
        }

        noFocusKeyCount++
        if (noFocusKeyCount < thresholdProvider()) return
        noFocusKeyCount = 0

        val now = SystemClock.uptimeMillis()
        if (now - lastWarnAt < WARN_COOLDOWN_MS) return // 최근 경고 직후면 지연 검증조차 하지 않음
        if (verifyPending) return                       // 이미 검증 대기 중이면 중복 예약 방지

        // 임계값에 닿았지만 바로 띄우지 않는다. 저사양에서 늦게 도착하는 "입력 실착" 이벤트/포커스
        // 갱신을 기다린 뒤, 그 사이 편집 활동이나 포커스가 확인되면 경고를 취소한다(오경고 방지).
        verifyPending = true
        scheduleVerify(verifyAction)
    }

    /**
     * 카운터/검증 대기 상태 초기화. 평가 스레드를 내릴 때(설정 OFF·정리) 예약된 검증이 버려지면
     * [verifyPending] 이 true 로 남아 이후 경고가 영영 안 뜨므로, 그 경로에서 반드시 호출한다.
     */
    fun reset() {
        noFocusKeyCount = 0
        verifyPending = false
    }

    /**
     * 실제 문자를 만들어내는 키만 카운트.
     * 제외: Shift/Ctrl/Alt/Meta 등 모디파이어, Ctrl/Alt/Meta 조합 단축키, dead key.
     */
    private fun isTypingKey(event: KeyEvent): Boolean {
        if (KeyEvent.isModifierKey(event.keyCode)) return false
        // Ctrl/Alt/Meta 가 눌린 조합은 단축키이지 타이핑이 아니다.
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        // unicodeChar != 0 이면 문자 생성 키 (스페이스 포함). 조합(dead key) 플래그는 제외.
        val unicode = event.unicodeChar and KeyCharacterMap.COMBINING_ACCENT.inv()
        return unicode != 0
    }

    companion object {
        /** 경고 1회 후 재경고 억제 시간. */
        const val WARN_COOLDOWN_MS = 2500L

        /**
         * 임계값 도달 후 실제 경고까지의 지연 검증 시간(ms). 저사양 기기에서 늦게 도착하는 입력 실착
         * 이벤트/포커스 갱신을 이 시간 동안 기다렸다가, 확인되면 경고를 취소한다. 진짜 포커스가 없으면
         * 그 이벤트가 오지 않아 이 시간 뒤 경고가 정상 발동한다(사용자가 인지하기엔 여전히 충분히 빠름).
         */
        const val WARN_VERIFY_DELAY_MS = 400L
    }
}
