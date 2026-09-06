package com.langsense.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.core.content.ContextCompat
import com.langsense.app.util.ImeLocaleParser

/**
 * IME 서브타입(언어) 변경 감지 (Feature 1).
 *
 * 전환을 **즉시** 잡기 위해 세 가지 경로를 병행한다(가장 먼저 도착한 신호가 발동):
 *  1) [BroadcastReceiver] — `ACTION_INPUT_METHOD_CHANGED` (IME/서브타입 전체 전환)
 *  2) [ContentObserver]   — `Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE` 변경
 *     (한/영 토글 시 시스템 설정이 즉시 바뀌므로, 화면 변화 없이 가만히 있어도 감지됨 → Bug 4)
 *  3) `TYPE_WINDOW_STATE_CHANGED` + Samsung One UI 팝업 텍스트 (백스톱 / 삼성 내부 토글 대응)
 *
 * ### 권위 소스 읽기 — "2회 깜박임 / 반응 씹힘" 근본 수정
 * 과거에는 현재 언어를 `imm.currentInputMethodSubtype` 로만 읽었는데, 이 값은 전환 직후 한동안
 * **이전 언어로 캐시 지연(stale)** 될 수 있다. 그 stale 읽기가 "직전 언어로의 새 전환"으로 오인돼
 * 다른 색 2번째 깜박임을 만들었고, 이를 막으려 쌓은 시간 가드(불응기 450ms/플랩 1000ms/에피소드
 * 700ms)가 이번엔 1초 안의 **정상 빠른 재전환까지 차단**해 "반응이 씹히는" 부작용을 낳았다.
 *
 * 이제 현재 언어는 [readAuthoritative] 가 **ContentObserver 가 감시하는 바로 그 설정값**
 * (`selected_input_method_subtype` 해시 + `default_input_method`)에서 직접 읽어 서브타입 목록과
 * 매핑한다 — 신호가 도착한 시점엔 설정값이 이미 갱신돼 있으므로 캐시 지연이 없다(stale 원천 제거).
 * 권위 값은 **관측 키가 실제로 바뀌었을 때만** 발동 근거가 된다([lastAuthKey] 참조). 덕분에:
 *  - 전환의 잔여 신호는 "권위 키 불변"으로 자연스럽게 no-op → 시간 가드 없이 중복 차단.
 *  - 불응기/플랩 가드를 제거해 1초 안의 정상 한→영→한 재전환도 그대로 발동(씹힘 해소).
 * 매핑이 불가능한 기기/IME 에서만 기존 `imm.currentInputMethodSubtype` 로 폴백하며([immFallbackLang]),
 * 폴백/팝업 값은 비권위(stale 가능)로 표시되어 [emitIfChanged] 의 메아리 가드([ECHO_GUARD_MS])를
 * 적용받는다(폴백 기기는 이전과 동일한 보호 유지 — 회귀 없음).
 *
 * ### 중복 발화 합치기 + no-op 재시도
 * 세 경로는 한 번의 한/영 전환에서 거의 동시에 여러 신호를 쏟아낸다. 모든 신호는 [requestRecheck]
 * 로 모여 [COALESCE_MS] 동안 하나로 합쳐지고(굶주림 방지 상한 [COALESCE_MAX_WAIT_MS]),
 * 마지막 신호 직후 한 번만 [runRecheck] → [emitIfChanged] 한다. 평가 시점에 설정값 전파가 아직
 * 안 끝난 저사양 기기를 위해, 강한 신호(브로드캐스트/옵저버/팝업)였는데 "변경 없음"이면
 * [RETRY_BACKOFF_MS] 백오프로 최대 2회 재확인해 전환이 조용히 유실되지 않게 한다(씹힘 해소).
 *
 * 어느 경로든 결과는 [emitIfChanged] 로 모이며, 이전 언어와 다를 때만 [onLanguageChanged] 호출.
 */
class ImeStateDetector(
    context: Context,
    private val onLanguageChanged: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val handler = Handler(Looper.getMainLooper())

    /** 마지막으로 발동한(=현재로 아는) 언어. null 이면 아직 모름. */
    private var lastLang: String? = null

    /**
     * 등록 성공 여부를 리시버/옵저버 **개별로** 추적한다. 과거처럼 둘을 AND 한 단일 플래그면,
     * 한쪽만 실패했을 때 stop() 이 성공한 쪽을 해제하지 못해(리시버 누수) 재연결 시 detector 가
     * 중복 등록되고 한 전환에 여러 번 발동하는(=2회 깜박임) 원인이 됐다.
     */
    private var receiverRegistered = false
    private var observerRegistered = false

    /** 합치는 구간 동안 들어온 윈도우 팝업 텍스트 힌트(삼성 내부 토글 대응). 매 [runRecheck] 후 비운다. */
    private var pendingPopupHint: String? = null

    /** 합쳐진 단 하나의 재확인 작업. 신호가 올 때마다 취소 후 재예약된다. */
    private val recheckRunnable = Runnable { runRecheck() }

    /** 현재 합치기 배치의 첫 신호 시각(uptime). 0 이면 보류 중 배치 없음. 굶주림 방지 상한 계산용. */
    private var pendingSince = 0L

    /** "변경 없음"으로 끝난 평가를 백오프 재확인할 남은 횟수. 강한 신호가 올 때마다 충전된다. */
    private var retriesLeft = 0

    /**
     * 현재 재확인 체인(합치기 배치 + 백오프 재시도 전체)이 강한 신호에서 비롯됐는지.
     * 폴백 기기(권위 매핑 불가)에서 stale 가능한 IMM 값은 **강한 신호가 포함된 체인에서만**
     * 발동 근거로 쓴다 — 그렇지 않으면 발동 후 [ECHO_GUARD_MS] 가 지난 뒤 아무 앱의 윈도우
     * 이벤트(약한 신호)가 stale IMM 을 "직전 언어로의 새 전환"으로 재발동시켜, 곧이은 정정
     * 발동이 그 플래시를 절단하는 "짧은 추가 깜박임"을 만든다. 시간 가드가 아니라 신호 출처
     * 게이트라 정상 빠른 재전환과 무관하다(회귀 없음).
     * 약한 신호는 절대 false 로 덮어쓰지 않고(백오프 대기 중 클로버 방지), 발동 시·재시도
     * 예산 소진 시·stop() 에서만 리셋한다.
     */
    private var pendingStrong = false

    /** 발동 횟수(전환 1회당 1 증가). 한 전환에 정확히 1회만 늘어야 함을 로그로 검증하기 위함. */
    private var emitCount = 0

    /** 마지막 발동 시각(비권위 값 메아리 가드 계산용). */
    private var lastEmitAt = 0L

    /**
     * 직전 발동에서 "벗어난" 언어(= 그 전의 lastLang). 비권위 소스(팝업 힌트/IMM 폴백)가
     * [ECHO_GUARD_MS] 안에 이 언어로 되돌아가는 값을 내면 stale 메아리로 보고 무시한다.
     * 권위 소스(설정값 직접 읽기)에는 적용하지 않으므로 정상 빠른 재전환은 놓치지 않는다.
     */
    private var prevLang: String? = null

    /**
     * 마지막으로 관측한 권위 소스 키("IME id:서브타입 해시"). 권위 값은 **이 키가 실제로 바뀌었을
     * 때만** 발동 근거가 된다 — lastLang 과 다르다는 이유만으로 발동하면, 서브타입이 안 바뀌는
     * 삼성 내부 토글 기기에서 힌트로 발동한 언어를 (변하지 않은) 설정값이 도로 뒤집는 새 중복
     * 깜박임이 생기기 때문. "설정값의 변화"만 전환 증거로 취급한다.
     */
    private var lastAuthKey: String? = null

    /**
     * "권위 역행" 지연 확인 상태. 일부 IME 는 한 번의 토글에 `selected_input_method_subtype` 를
     * 두 번 기록한다(중간값→최종값). 중간값이 직전 언어([prevLang])로 읽히면 진짜 빠른 재전환과
     * 구별이 안 되므로, 발동 직후([REVERT_CONFIRM_WINDOW_MS] 안) 직전 언어로 되돌아가는 권위 키는
     * 즉시 발동하지 않고 [REVERT_CONFIRM_DELAY_MS] 뒤 재확인한다 — 같은 키가 유지되면 진짜
     * 재전환으로 발동, 그 사이 최종값으로 넘어갔으면 자연 no-op(중복 깜박임 소스 차단).
     * 차단(가드)이 아니라 지연이므로 정상 빠른 재전환은 최대 250ms 늦게 발동할 뿐 유실되지 않는다.
     */
    private var pendingRevertKey: String? = null
    private var revertDeferredAt = 0L

    /** 현재 IME id 기준 서브타입 목록 캐시(권위 소스 매핑용 — IME 가 바뀔 때만 재조회해 IPC 최소화). */
    private var cachedImeId: String? = null
    private var cachedSubtypes: List<InputMethodSubtype> = emptyList()

    /** 캐시 갱신으로도 매핑에 실패한 해시. 같은 해시로 반복 재조회(IPC)하지 않기 위한 기억. */
    private var lastUnresolvedHash = 0

    private val imeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = requestRecheck(strong = true)
    }

    private val subtypeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = requestRecheck(strong = true)
    }

    /** 서비스 시작 시 호출: 리스너 등록 + 현재 언어 캐시(플래시 없이). 현재 언어 코드 반환. */
    fun start(): String {
        registerListeners()
        if (!receiverRegistered || !observerRegistered) {
            // 주 감지 경로(옵저버/리시버)가 죽은 채 방치되면 "화면이 안 바뀌면 반응이 없는" 상태가
            // 된다. 시스템 부하로 일시 실패했을 수 있으므로 잠시 뒤 1회 재등록을 시도한다
            // (stop() 의 removeCallbacksAndMessages 가 취소를 담당).
            handler.postDelayed(registerRetry, REGISTER_RETRY_MS)
        }
        val auth = readAuthoritative()
        if (auth != null) lastAuthKey = auth.key
        val lang = auth?.lang ?: immFallbackLang()
        if (lang != ImeLocaleParser.UNKNOWN) {
            lastLang = lang
        }
        return lang
    }

    private val registerRetry = Runnable {
        registerListeners()
        if (!receiverRegistered || !observerRegistered) {
            Log.w(TAG, "listener re-registration still incomplete (receiver=$receiverRegistered, observer=$observerRegistered)")
        }
    }

    /** 리시버/옵저버 등록. 실패한 쪽만 다시 시도한다(성공한 쪽은 그대로). */
    private fun registerListeners() {
        // 등록이 실제로 실패했을 때 무조건 성공으로 기록하면 주 감지 경로가 조용히 죽은 채 재등록도
        // 안 된다. 개별 플래그로 성공 여부를 반영해, 부분 실패 시 실패한 쪽만 재시도.
        if (!receiverRegistered) {
            receiverRegistered = runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    imeChangeReceiver,
                    IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }.isSuccess
        }
        if (!observerRegistered) {
            observerRegistered = runCatching {
                val cr = appContext.contentResolver
                // Settings.Secure 의 일부 IME 키 상수는 @hide 이므로 안정적인 문자열 키를 직접 사용한다.
                cr.registerContentObserver(
                    Settings.Secure.getUriFor(KEY_SELECTED_SUBTYPE),
                    false, subtypeObserver
                )
                cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                    false, subtypeObserver
                )
            }.isSuccess
        }
        if (!receiverRegistered || !observerRegistered) {
            Log.w(TAG, "listener registration incomplete (receiver=$receiverRegistered, observer=$observerRegistered)")
        }
    }

    /**
     * 서비스 종료 시 호출: 리스너 해제 + 예약된 재확인 콜백 정리.
     * 해제는 등록 성공 여부와 무관하게 **무조건** 시도한다(runCatching) — 부분 등록 실패 상태에서
     * 성공했던 쪽이 해제되지 않고 살아남는 누수(→ 재연결 시 중복 발동)를 막는다.
     */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        pendingPopupHint = null
        pendingSince = 0L
        retriesLeft = 0
        pendingStrong = false
        pendingRevertKey = null
        revertDeferredAt = 0L
        runCatching { appContext.unregisterReceiver(imeChangeReceiver) }
        runCatching { appContext.contentResolver.unregisterContentObserver(subtypeObserver) }
        receiverRegistered = false
        observerRegistered = false
    }

    /** 접근성 윈도우 이벤트 경로(백스톱 + Samsung 팝업 텍스트 fallback). */
    fun onWindowStateChanged(event: AccessibilityEvent) {
        val popupLang = if (isSystemPopupSource(event)) {
            ImeLocaleParser.parseFromSystemPopupText(eventText(event))
        } else null
        // 팝업 텍스트가 잡혔고 **그 내용이 새 정보일 때만** "강한 신호"(재시도 충전). 현재 언어를
        // 그대로 말하는 후행 팝업까지 강한 신호로 치면, 폴백 기기에서 재확인 체인이 메아리 가드
        // (ECHO_GUARD_MS) 너머까지 이어져 stale IMM 값이 재발동하는 연료가 된다. 일반 윈도우
        // 이벤트는 백스톱 재확인만 하고 재시도 예산은 건드리지 않는다(시스템 전체 윈도우 변화마다
        // 오기 때문).
        requestRecheck(popupLang, strong = popupLang != null && popupLang != lastLang)
    }

    /**
     * 모든 감지 경로(Broadcast/Observer/윈도우 이벤트)의 신호를 [COALESCE_MS] 동안 하나로 합친다.
     *
     * 신호가 올 때마다 기존 예약을 취소하고 다시 예약하므로, 한 번의 전환에서 쏟아지는 여러 신호는
     * 마지막 신호 직후 **단 한 번의** [runRecheck] 로 수렴한다. 단, 신호가 [COALESCE_MS] 이내 간격으로
     * 끝없이 이어지면 평가가 영원히 미뤄질 수 있으므로(굶주림), 첫 신호 후 [COALESCE_MAX_WAIT_MS] 가
     * 지나면 강제로 즉시 평가한다.
     *
     * @param popupHint 윈도우 이벤트에서 파싱한 팝업 언어(있으면 서브타입보다 우선 — 삼성 내부 토글 대응).
     * @param strong 브로드캐스트/옵저버/팝업처럼 "전환이 실제로 있었다"고 볼 신호인지. 강한 신호만
     *   no-op 재시도 예산([retriesLeft])을 충전한다.
     */
    private fun requestRecheck(popupHint: String? = null, strong: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (popupHint != null && popupHint != ImeLocaleParser.UNKNOWN) {
            // 팝업 힌트는 비권위 소스: ① 현재 언어와 같으면 새 정보가 없고(폐기),
            // ② 방금 벗어난 언어로 곧장 되돌아가는 값이면 삼성 팝업의 지연된 메아리(폐기).
            // 그 외에는 저장 — 서브타입이 안 바뀌는 삼성 내부 토글의 연속 전환도 놓치지 않는다.
            val noNews = popupHint == lastLang
            val echo = popupHint == prevLang && now - lastEmitAt < ECHO_GUARD_MS
            if (!noNews && !echo) pendingPopupHint = popupHint
        }
        if (strong) {
            retriesLeft = MAX_NOOP_RETRIES
            pendingStrong = true
        }
        if (pendingSince == 0L) pendingSince = now
        handler.removeCallbacks(recheckRunnable)
        val delay = if (now - pendingSince >= COALESCE_MAX_WAIT_MS) 0L else COALESCE_MS
        handler.postDelayed(recheckRunnable, delay)
    }

    /**
     * 합쳐진 단일 재확인.
     *
     * 1) 권위 소스(설정값)를 먼저 평가하되, **관측 키가 실제로 바뀌었을 때만** 발동 근거로 삼는다.
     *    (키 불변 = 설정값에 새 정보 없음. lastLang 과 다르다는 이유로 발동하면 삼성 내부 토글
     *    기기에서 힌트 발동을 도로 뒤집는 중복 깜박임이 생긴다.)
     * 2) 권위 키가 그대로면 팝업 힌트가 유일한 신호(삼성 내부 토글: 서브타입 불변, 팝업만 변경).
     *    권위 키가 바뀌었는데 힌트가 그와 다르면 힌트는 stale 이므로 버려진다.
     * 3) 권위 소스 매핑이 불가한 기기는 기존 폴백(힌트 우선 → IMM 캐시, 모두 비권위)으로 흐른다.
     *
     * 어느 경로로도 발동하지 못했으면 — 설정값 전파가 아직 안 끝난 저사양 기기일 수 있으므로 —
     * 강한 신호가 있었던 경우에 한해 [RETRY_BACKOFF_MS] 백오프로 최대 [MAX_NOOP_RETRIES] 회
     * 재확인한다(전환의 조용한 유실 방지).
     */
    private fun runRecheck() {
        // 메인 루퍼 Runnable 에서 예외가 새면 접근성 서비스 프로세스가 통째로 죽는다(→ 시스템 재바인드에
        // 의존, 일부 ROM 은 서비스를 꺼버림). 감지 1회를 잃는 편이 훨씬 싸다.
        try {
            runRecheckInner()
        } catch (t: Throwable) {
            Log.w(TAG, "recheck failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun runRecheckInner() {
        pendingSince = 0L
        val hint = pendingPopupHint
        pendingPopupHint = null

        val auth = readAuthoritative()
        if (auth != null) {
            val changed = auth.key != lastAuthKey
            // 발동 직후 직전 언어로 되돌아가는 키 변경은 서브타입 이중 기록(바운스)의 중간값일 수
            // 있으므로, lastAuthKey 를 갱신하지 않은 채 잠깐 미뤄 재확인한다(주석: pendingRevertKey).
            if (changed && deferSuspectRevert(auth)) return
            if (!changed) pendingRevertKey = null // 바운스가 원래 키로 되돌아와 소멸한 경우 등
            lastAuthKey = auth.key
            if (changed && emitIfChanged(auth.lang, authoritative = true)) return
            if (!changed && hint != null && emitIfChanged(hint, authoritative = false)) return
        } else {
            if (hint != null && emitIfChanged(hint, authoritative = false)) return
            // IMM 폴백은 stale 가능 → 강한 신호에서 비롯된 체인에서만 발동 근거로 인정.
            if (pendingStrong && emitIfChanged(immFallbackLang(), authoritative = false)) return
        }
        if (retriesLeft > 0) {
            val attempt = MAX_NOOP_RETRIES - retriesLeft
            retriesLeft--
            handler.postDelayed(recheckRunnable, RETRY_BACKOFF_MS[attempt])
        } else {
            pendingStrong = false // 발동 없이 재시도 예산 소진 → 체인 종료
        }
    }

    /**
     * "권위 역행" 의심 판정 + 지연 확인 예약. true 를 반환하면 이번 평가에서 발동을 보류한 것이다
     * (호출부는 lastAuthKey 를 갱신하지 않고 종료 — 재확인 시 changed 판정이 유지되도록).
     *
     * 의심 조건: 키가 바뀌었고, 그 언어가 직전에 벗어난 언어([prevLang])이며, 마지막 발동 후
     * [REVERT_CONFIRM_WINDOW_MS] 이내. 의심되면 [REVERT_CONFIRM_DELAY_MS] 뒤 재확인을 예약하고,
     * 재확인 시점에도 같은 키가 유지되어 있으면 진짜 재전환으로 확인(false 반환 → 발동 진행).
     */
    private fun deferSuspectRevert(auth: AuthReading): Boolean {
        val now = SystemClock.uptimeMillis()
        val suspect = auth.lang == prevLang && now - lastEmitAt < REVERT_CONFIRM_WINDOW_MS
        if (!suspect) {
            pendingRevertKey = null
            return false
        }
        if (pendingRevertKey == auth.key && now - revertDeferredAt >= REVERT_CONFIRM_DELAY_MS) {
            pendingRevertKey = null // 유예 후에도 같은 키 → 진짜 재전환으로 확인
            return false
        }
        if (pendingRevertKey != auth.key) {
            pendingRevertKey = auth.key
            revertDeferredAt = now
        }
        // 다른 신호가 합치기 창을 다시 열어도 유예 시각(revertDeferredAt)은 유지되므로,
        // 남은 유예 시간만큼만 재예약해 확인 지연이 늘어나지 않게 한다.
        val remaining = (REVERT_CONFIRM_DELAY_MS - (now - revertDeferredAt)).coerceAtLeast(1L)
        handler.removeCallbacks(recheckRunnable)
        handler.postDelayed(recheckRunnable, remaining)
        return true
    }

    /**
     * 언어가 실제로 바뀌었을 때만 발동. 발동했으면 true.
     *
     * 비권위 값(팝업 힌트/IMM 폴백)에만 좁은 메아리 가드를 적용한다: 방금 벗어난 언어([prevLang])로
     * [ECHO_GUARD_MS] 안에 되돌아가는 값은 stale 메아리로 무시. 권위 값(설정 직접 읽기)은 정의상
     * stale 이 없으므로 가드 없이 신뢰한다 → 1초 안의 정상 한→영→한 재전환도 그대로 발동(씹힘 해소).
     */
    private fun emitIfChanged(lang: String, authoritative: Boolean): Boolean {
        if (lang == ImeLocaleParser.UNKNOWN) return false
        val last = lastLang
        if (lang == last) return false
        if (!authoritative && lang == prevLang &&
            SystemClock.uptimeMillis() - lastEmitAt < ECHO_GUARD_MS
        ) return false
        prevLang = last
        lastLang = lang
        lastEmitAt = SystemClock.uptimeMillis()
        retriesLeft = 0 // 발동했으면 이 배치의 재시도는 끝
        pendingStrong = false // 발동 = 체인 종료
        emitCount++
        // 한 전환당 정확히 1회만 찍혀야 한다(실기기 검증용 로그).
        Log.d(TAG, "language change emitted: $lang (emit #$emitCount, authoritative=$authoritative)")
        onLanguageChanged(lang)
        return true
    }

    /** 권위 소스 읽기 결과: 관측 키("IME id:서브타입 해시") + 파싱된 언어. */
    private data class AuthReading(val key: String, val lang: String)

    /**
     * 권위 소스 읽기 — `selected_input_method_subtype`(서브타입 hashCode) + `default_input_method`
     * (IME id) 를 Settings 에서 직접 읽고, 해당 IME 의 활성 서브타입 목록에서 hashCode 가 일치하는
     * 서브타입의 locale 을 파싱한다. ContentObserver 가 감시하는 값 그 자체라 신호 시점에 이미
     * 갱신돼 있다(캐시 지연 없음). 매핑 실패(값 없음/목록에 없음/locale 파싱 실패) 시 null —
     * 호출부가 [immFallbackLang] 폴백 경로로 흐른다.
     */
    private fun readAuthoritative(): AuthReading? {
        val cr = appContext.contentResolver
        val hash = runCatching { Settings.Secure.getInt(cr, KEY_SELECTED_SUBTYPE, INVALID_HASH) }
            .getOrDefault(INVALID_HASH)
        if (hash == INVALID_HASH) return null
        val imeId = runCatching {
            Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        if (imeId.isNullOrEmpty()) return null
        val lang = ImeLocaleParser.parseLocale(subtypeForHash(imeId, hash))
        if (lang == ImeLocaleParser.UNKNOWN) return null
        return AuthReading("$imeId:$hash", lang)
    }

    /** 폴백: 기존 `imm.currentInputMethodSubtype` 읽기(전환 직후 stale 가능 → 비권위 취급). */
    private fun immFallbackLang(): String =
        ImeLocaleParser.parseLocale(runCatching { imm.currentInputMethodSubtype }.getOrNull())

    /**
     * 서브타입 hashCode → [InputMethodSubtype] 매핑. 목록은 IME id 기준으로 캐시하고,
     * 캐시 미스(사용자가 서브타입을 새로 추가한 경우 등)일 때만 1회 재조회한다.
     * 재조회로도 못 찾은 해시는 기억해 두어 같은 해시로 IPC 를 반복하지 않는다.
     */
    private fun subtypeForHash(imeId: String, hash: Int): InputMethodSubtype? {
        if (imeId != cachedImeId) refreshSubtypeCache(imeId)
        cachedSubtypes.firstOrNull { it.hashCode() == hash }?.let { return it }
        if (hash == lastUnresolvedHash) return null
        refreshSubtypeCache(imeId)
        val found = cachedSubtypes.firstOrNull { it.hashCode() == hash }
        if (found == null) lastUnresolvedHash = hash
        return found
    }

    private fun refreshSubtypeCache(imeId: String) {
        cachedSubtypes = runCatching {
            imm.enabledInputMethodList.firstOrNull { it.id == imeId }
                ?.let { imm.getEnabledInputMethodSubtypeList(it, true) }
        }.getOrNull().orEmpty()
        cachedImeId = imeId
        lastUnresolvedHash = 0
    }

    /** Samsung/시스템 팝업으로 보이는 이벤트만 텍스트 fallback 대상으로. */
    private fun isSystemPopupSource(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        return pkg == "android" || pkg.contains("samsung", ignoreCase = true) ||
            pkg.contains("inputmethod", ignoreCase = true) || pkg.contains("honeyboard", ignoreCase = true)
    }

    private fun eventText(event: AccessibilityEvent): String =
        event.text.joinToString(" ") { it?.toString().orEmpty() }

    companion object {
        private const val TAG = "ImeStateDetector"

        /** `Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE` 가 @hide 라 문자열 키를 직접 사용. */
        private const val KEY_SELECTED_SUBTYPE = "selected_input_method_subtype"

        /** `selected_input_method_subtype` 미설정/읽기 실패 표시값(프레임워크도 -1 을 sentinel 로 씀). */
        private const val INVALID_HASH = -1

        /**
         * 여러 감지 신호를 합치는 구간(ms). 한/영 전환 1회가 만드는 신호 다발이 이 안에 들어오도록
         * 잡되, 신호가 멎은 뒤 설정값이 전파될 시간을 주는 값.
         */
        private const val COALESCE_MS = 150L

        /**
         * 합치기 최대 대기(ms). 신호가 [COALESCE_MS] 이내 간격으로 끝없이 이어져도(예: 삼성 IME 가
         * 윈도우 이벤트를 계속 뿜는 경우) 첫 신호 후 이 시간이 지나면 강제로 평가한다(굶주림 방지).
         */
        private const val COALESCE_MAX_WAIT_MS = 400L

        /**
         * 비권위 값(팝업 힌트/IMM 폴백) 전용 메아리 가드(ms). 방금 벗어난 언어로 이 시간 안에
         * 되돌아가는 비권위 값은 지연된 stale 신호로 보고 무시한다. 과거 플랩 가드와 같은 폭이지만
         * **권위 값(설정 직접 읽기)에는 적용하지 않는다**는 점이 다르다 — 권위 소스가 동작하는
         * 대다수 기기에서 정상 빠른 재전환(1초 내 한→영→한)을 더는 놓치지 않고, 비권위 폴백만
         * 이전과 동일한 보호를 유지한다(폴백 기기 회귀 없음).
         */
        private const val ECHO_GUARD_MS = 1000L

        /** "변경 없음" 평가 후 백오프 재확인 횟수/간격. 저사양 기기의 늦은 설정 전파를 흡수한다. */
        private const val MAX_NOOP_RETRIES = 2
        private val RETRY_BACKOFF_MS = longArrayOf(200L, 400L)

        /**
         * "권위 역행" 의심 창(ms): 마지막 발동 후 이 시간 안에 직전 언어로 되돌아가는 권위 키
         * 변경은 서브타입 이중 기록(바운스)의 중간값일 수 있어 지연 확인 대상이 된다.
         * 바운스의 두 기록은 실질 동시(수십 ms 간격)지만, 저사양 전파 지연 시 관측은 합치기
         * 상한([COALESCE_MAX_WAIT_MS]=400) + 백오프(200+400) = 최대 ~1000ms 까지 벌어질 수
         * 있어 그보다 여유 있게 잡는다(과거 600ms 는 자기 케이던스를 못 덮어 늦은 바운스가
         * 의심 판정을 탈출했다). 차단이 아니라 250ms 지연 확인이므로 이 창 안의 진짜 재전환도
         * 유실되지 않는다.
         */
        private const val REVERT_CONFIRM_WINDOW_MS = 1200L

        /** 권위 역행 의심 시 재확인까지의 유예(ms). 바운스 최종값이 설정에 반영되기에 충분한 시간. */
        private const val REVERT_CONFIRM_DELAY_MS = 250L

        /** 리시버/옵저버 등록 실패 시 재등록까지의 지연(ms). */
        private const val REGISTER_RETRY_MS = 5000L
    }
}
