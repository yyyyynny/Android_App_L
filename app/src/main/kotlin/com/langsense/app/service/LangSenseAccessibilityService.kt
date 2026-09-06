package com.langsense.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.langsense.app.R
import com.langsense.app.overlay.OverlayManager
import com.langsense.app.overlay.QuickMenuItem
import com.langsense.app.ui.MainActivity
import com.langsense.app.ui.SettingsActivity
import com.langsense.app.util.HardwareKeyboardDetector
import com.langsense.app.util.ImeLocaleParser
import com.langsense.app.util.Prefs

/**
 * kIkI 접근성 서비스 — 모든 감지의 진입점. (클래스명은 식별자라 LangSenseAccessibilityService 유지)
 *
 * - onAccessibilityEvent: 윈도우 상태 변경(언어 전환) / 텍스트 선택(한영타)
 * - onKeyEvent: 포커스 없는 키 입력 카운트 (절대 소비하지 않음)
 *
 * ### 생존성 원칙
 * 접근성 서비스에서 uncaught exception 이 나면 프로세스가 죽고, 시스템 재바인드에만 의존하게 된다
 * (일부 ROM 은 이때 접근성 토글을 꺼버려 사용자가 수동으로 다시 켜야 한다). 그리고 재초기화
 * ([initialize]) 도중 예외가 나면 `initialized=false` 로 굳어 "살아있지만 완전 무반응"이 된다.
 * 그래서 모든 시스템 콜백과 백그라운드 작업은 [guarded] 로 감싸고(감지 1회를 잃는 편이 훨씬 싸다),
 * 초기화 실패는 잠시 뒤 자동 재시도한다.
 *
 * ### 저사양 원칙
 * 서비스 설정(구독 이벤트/플래그)은 정적 XML 이 아니라 [syncServiceInfo] 가 **켜진 기능만큼만**
 * 런타임에 좁힌다 — 예컨대 포커스 경고를 끈 사용자는 키 필터(키당 Binder 동기 왕복 2회)와
 * 텍스트 변경 이벤트를 아예 받지 않는다. 노드 IPC 는 이벤트당 최대 1회, 포커스 조회는 짧게 캐시.
 *
 * 보안 원칙: 키스트로크/화면 내용을 수집·저장·전송하지 않으며 모든 처리는 온디바이스 로컬.
 */
class LangSenseAccessibilityService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayManager
    private lateinit var imeDetector: ImeStateDetector
    private lateinit var keyMonitor: KeyEventMonitor
    private lateinit var selectionMonitor: TextSelectionMonitor

    /** 외장(하드웨어) 키보드 연결 감지 — "터치 키보드 제외" 옵션이 켜져 있을 때만 존재(추가 기능 2). */
    private var keyboardDetector: HardwareKeyboardDetector? = null

    private var currentLang: String = ImeLocaleParser.UNKNOWN
    private var initialized = false

    /** [initialize] 실패 시 재시도 횟수(연결당 리셋). */
    private var initAttempt = 0
    private val initRetry = Runnable { initialize() }

    /**
     * 편집칸에 글자가 실제로 들어간 마지막 시각(uptime). text/selection 변경 이벤트가 편집 노드에서
     * 올 때(메인 스레드) 갱신한다. 포커스 없는 키 입력 경고의 오발동(입력은 되는데 경고가 뜨는 문제)을
     * 막는 데 쓴다. 갱신은 메인 스레드에서만 하지만, 경고 지연 검증이 키 평가(백그라운드) 스레드에서
     * 이 값을 다시 읽으므로 64비트 torn read 방지를 위해 @Volatile 로 둔다.
     */
    @Volatile
    private var lastEditableActivityAt = 0L

    /**
     * 편집 여부를 마지막으로 **확인한** 시각(메인 전용). 결과와 무관하게 갱신한다 — 과거엔 편집 노드일
     * 때만 갱신해서, 비편집 TextView 가 텍스트를 계속 바꾸는 화면(시계·채팅·재생 시간)에서는
     * 300ms 가드가 영영 통과되지 않아 이벤트마다 노드 IPC 가 돌았다.
     */
    private var lastEditCheckAt = 0L

    /**
     * 포커스 없는 키 입력 평가(접근성 노드 IPC)를 처리하는 백그라운드 스레드 (Bug 1).
     * onKeyEvent 가 도는 메인 스레드에서 무거운 조회를 빼내 키 디스패치가 막히지 않게 한다.
     * 포커스 경고가 켜져 있을 때만 존재한다([syncKeyEvalThread]).
     *
     * 평가 스레드에서도 [keyEvalHandler] 를 읽으므로(scheduleVerify) @Volatile — 메인에서
     * cleanup 이 null 을 쓰는 동안 stale 참조를 보지 않게 한다.
     */
    @Volatile
    private var keyEvalThread: HandlerThread? = null

    @Volatile
    private var keyEvalHandler: Handler? = null

    /** 메인 스레드 예약용(윈도우 변경 디바운스, 초기화 재시도 등). */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** [scheduleSoftKeyboardRecheck] 재평가 예약이 이미 걸려 있는지. */
    private var softKeyboardRecheckPending = false
    private val softKeyboardRecheck = Runnable {
        softKeyboardRecheckPending = false
        guarded("softKeyboardRecheck") { refreshSoftKeyboardState() }
    }

    /** 배지 재부착을 마지막으로 시도한 시각(uptime) — [retryBadgeIfMissing] 간격 제한. */
    private var lastBadgeRetryAt = 0L

    /**
     * 포커스 조회 결과 캐시(평가 스레드 전용, 값 자체는 @Volatile 로 두어 cleanup 리셋이 보이게).
     * 연타 시 키마다 `rootInActiveWindow`+`findFocus`(+실패 시 전체 윈도우 순회) IPC 가 돌던 것을
     * [FOCUS_PROBE_CACHE_MS] 안에서는 재사용한다. 지연 검증은 캐시를 거치지 않는다(오경고 고정 방지).
     */
    @Volatile
    private var lastFocusProbeAt = 0L

    @Volatile
    private var lastFocusProbeResult = false

    // ---- KeyEventMonitor 에 1회 주입하는 콜백들(키마다 람다를 만들지 않기 위해 필드로) ----
    private val cachedFocusProbe: () -> Boolean = {
        val now = SystemClock.uptimeMillis()
        if (now - lastFocusProbeAt < FOCUS_PROBE_CACHE_MS) {
            lastFocusProbeResult
        } else {
            val r = hasActiveEditableFocus() || hasAnyEditableFocus()
            lastFocusProbeResult = r
            lastFocusProbeAt = now
            r
        }
    }
    private val freshFocusProbe: () -> Boolean = { hasActiveEditableFocus() || hasAnyEditableFocus() }
    private val scheduleVerify: (() -> Unit) -> Unit = { action ->
        keyEvalHandler?.postDelayed({ guarded("verify") { action() } }, KeyEventMonitor.WARN_VERIFY_DELAY_MS)
    }
    private val recheckRecentEditableActivity: () -> Boolean = {
        SystemClock.uptimeMillis() - lastEditableActivityAt < RECENT_INPUT_MS
    }

    // ---------------------------------------------------------------------
    // 생명주기
    // ---------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        initAttempt = 0
        initialize()
    }

    /**
     * 초기화(멱등). onServiceConnected 는 같은 인스턴스에서 다시 호출될 수 있다(설정 변경/재바인드) —
     * 정리 없이 재초기화하면 BroadcastReceiver/ContentObserver 가 중복 등록되어 한 번의 한/영 전환에
     * 여러 detector 가 각각 발동하므로 먼저 이전 상태를 정리한다.
     *
     * 실패하면 부분 상태를 해제하고 [INIT_RETRY_MS] 뒤 최대 [INIT_MAX_RETRY] 회 재시도한다 — 재시도
     * 예약은 반드시 [cleanup] **뒤**에 한다(cleanup 이 mainHandler 콜백을 전부 지운다).
     */
    private fun initialize() {
        mainHandler.removeCallbacks(initRetry) // 시스템 재호출과 예약 재시도의 중복 방지
        if (initialized) cleanup()
        try {
            prefs = Prefs(this)
            overlay = OverlayManager(this, prefs)
            imeDetector = ImeStateDetector(this) { lang -> onLanguageChanged(lang) }
            keyMonitor = KeyEventMonitor(
                enabledProvider = { prefs.noFocusEnabled },
                thresholdProvider = { prefs.noFocusThreshold },
                focusProbe = cachedFocusProbe,
                verifyProbe = freshFocusProbe,
                scheduleVerify = scheduleVerify,
                recheckRecentEditableActivity = recheckRecentEditableActivity,
                onWarn = { overlay.showNoFocusWarning(getString(R.string.overlay_no_focus)) }
            )
            selectionMonitor = TextSelectionMonitor(
                confidencePercentProvider = { prefs.replaceConfidence }
            ) { node, fullText, selStart, selEnd, converted ->
                overlay.showReplaceChip(node, fullText, selStart, selEnd, converted)
            }
            overlay.setQuickMenuItems(buildQuickMenuItems())
            syncKeyEvalThread()
            syncKeyboardDetector()
            prefs.register(this)
            // 리스너 등록 + 현재 언어 캐시(플래시 없이)
            currentLang = imeDetector.start()
            syncServiceInfo()
            initialized = true
        } catch (t: Throwable) {
            Log.w(TAG, "init failed (attempt=$initAttempt): ${t.javaClass.simpleName}: ${t.message}")
            cleanup()
            if (initAttempt < INIT_MAX_RETRY) {
                initAttempt++
                mainHandler.postDelayed(initRetry, INIT_RETRY_MS)
            }
            return
        }
        // 초기 소프트 키보드 표시 상태 반영 후 배지 표시. 여기서의 실패는 "서비스 동작 불가"가
        // 아니므로 재시도 대상이 아니다(다음 이벤트에서 자연 복구).
        guarded("post-init") {
            refreshSoftKeyboardState()
            refreshBadge()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        initialized = false
        mainHandler.removeCallbacksAndMessages(null)
        softKeyboardRecheckPending = false
        if (::prefs.isInitialized) runCatching { prefs.unregister(this) }
        if (::imeDetector.isInitialized) runCatching { imeDetector.stop() }
        runCatching { keyboardDetector?.stop() }
        keyboardDetector = null
        if (::overlay.isInitialized) runCatching { overlay.removeAll() }
        stopKeyEvalThread()
        // 다음 초기화가 이전 세션의 판정을 물려받지 않게 상태 리셋.
        softKeyboardVisible = false
        lastBadgeRetryAt = 0L
        lastEditableActivityAt = 0L
        lastEditCheckAt = 0L
        lastFocusProbeAt = 0L
        lastFocusProbeResult = false
    }

    /**
     * 예외 격리. 시스템 콜백/핸들러 작업 안에서 새는 예외는 프로세스를 죽이므로 로그로 바꾼다.
     * (감지 1회 유실 < 서비스 사망)
     */
    private inline fun guarded(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$tag failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------------
    // 런타임 서비스 설정 — 켜진 기능만큼만 구독(저사양 핵심)
    // ---------------------------------------------------------------------

    /**
     * 구독 이벤트/플래그를 현재 설정에 맞춰 좁힌다. XML(`accessibility_service_config.xml`)은 최대
     * 집합이고 `can*` 능력만 고정하며, 실제 구독은 여기서 결정한다.
     *
     * - `TYPE_WINDOW_STATE_CHANGED`: 언어 전환 감지의 백스톱/삼성 팝업 — 항상.
     * - `TYPE_WINDOWS_CHANGED` + `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`: 터치 키보드 제외 ON 일 때만
     *   (이 이벤트는 시스템 전역의 창 생성/소멸마다 와서 초당 수십 건이다).
     * - `TYPE_VIEW_TEXT_SELECTION_CHANGED`: 한영타 교체 또는 포커스 경고(입력 실착 근거) ON 일 때.
     * - `TYPE_VIEW_TEXT_CHANGED` + `FLAG_REQUEST_FILTER_KEY_EVENTS`(+윈도우 순회 플래그): 포커스 경고
     *   ON 일 때만 — 키 필터는 모든 키가 포커스 앱으로 가기 전에 이 서비스 메인 스레드를 동기로
     *   경유하게 하므로(키당 Binder 왕복 2회) 꺼진 사용자에겐 순수 낭비다.
     */
    private fun syncServiceInfo() {
        val info = serviceInfo ?: run { Log.w(TAG, "serviceInfo unavailable"); return }
        val noFocus = prefs.noFocusEnabled
        val replace = prefs.replaceEnabled
        val excl = prefs.excludeTouchKeyboard

        var types = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (excl) types = types or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (replace || noFocus) types = types or AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        if (noFocus) types = types or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED

        val managed = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        var flags = info.flags and managed.inv() // 시스템이 붙인 다른 비트는 보존
        if (noFocus || excl) flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (noFocus) flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS

        if (info.eventTypes == types && info.flags == flags &&
            info.notificationTimeout == NOTIFICATION_TIMEOUT_MS
        ) return
        info.eventTypes = types
        info.flags = flags
        info.notificationTimeout = NOTIFICATION_TIMEOUT_MS
        runCatching { serviceInfo = info }
            .onFailure { Log.w(TAG, "setServiceInfo failed: ${it.javaClass.simpleName}") }
    }

    /** 포커스 경고가 켜져 있을 때만 키 평가 스레드를 유지한다(OS 스레드 1개 + 큐). */
    private fun syncKeyEvalThread() {
        val want = prefs.noFocusEnabled
        if (want && keyEvalThread == null) startKeyEvalThread()
        else if (!want && keyEvalThread != null) stopKeyEvalThread()
    }

    private fun startKeyEvalThread() {
        val t = HandlerThread("kIkI-keyeval")
        // 백스톱: HandlerThread 는 Runnable 이 던지면 루퍼만 죽고 큐는 살아남아(post 는 계속 true)
        // 조용히 기능이 멎는다. 1차 방어는 post 람다의 guarded 이고, 그래도 죽으면 메인에서 재생성.
        t.setUncaughtExceptionHandler { th, e ->
            Log.w(TAG, "keyeval thread died: ${e.javaClass.simpleName}: ${e.message}")
            mainHandler.post {
                if (initialized && keyEvalThread === th) {
                    stopKeyEvalThread()
                    if (prefs.noFocusEnabled) startKeyEvalThread()
                }
            }
        }
        t.start()
        keyEvalThread = t
        keyEvalHandler = Handler(t.looper)
    }

    private fun stopKeyEvalThread() {
        keyEvalHandler?.removeCallbacksAndMessages(null)
        keyEvalThread?.quitSafely()
        keyEvalThread = null
        keyEvalHandler = null
        // 예약돼 있던 지연 검증이 버려지면 verifyPending 이 true 로 남아 이후 경고가 영영 안 뜬다.
        if (::keyMonitor.isInitialized) keyMonitor.reset()
    }

    /**
     * 외장 키보드 감지기는 "터치 키보드 제외" 옵션이 켜져 있을 때만 필요하다(그 결과가 하는 일은
     * 소프트 키보드 재평가 1회뿐이고, 옵션 OFF 면 그 재평가가 즉시 return 이라 순수 오버헤드).
     */
    private fun syncKeyboardDetector() {
        val want = prefs.excludeTouchKeyboard
        if (want && keyboardDetector == null) {
            keyboardDetector = HardwareKeyboardDetector(this) { onKeyboardPresenceChanged() }
                .also { it.start() }
        } else if (!want && keyboardDetector != null) {
            runCatching { keyboardDetector?.stop() }
            keyboardDetector = null
        }
    }

    // ---------------------------------------------------------------------
    // 터치 키보드 제외 (추가 기능 2)
    // ---------------------------------------------------------------------

    /** 소프트 키보드(IME 창)가 현재 화면에 떠 있는지 캐시. "터치 키보드 제외" 게이트 기준(추가 기능 2). */
    private var softKeyboardVisible = false

    /**
     * 기능 활성 여부 (추가 기능 2).
     * "터치 키보드 제외"가 꺼져 있으면 항상 활성(기존 동작). 켜져 있으면 **소프트 키보드(터치 입력)가
     * 떠 있지 않을 때만** 활성 — 즉 외장 키보드로 입력 중(소프트 키보드 숨김)이면 활성, 화면 터치
     * 키보드를 쓰는 동안(소프트 키보드 표시)이면 플래시/배지/경고/교체를 모두 끈다.
     *
     * 'HW 키보드 연결 여부'가 아니라 '소프트 키보드 표시 여부'로 판정하는 이유: 외장 키보드를 상시
     * 연결해 두는 사용자는 연결 기준이면 늘 활성이라 터치 입력 시 꺼지지 않는다(기존 미작동 원인).
     */
    private fun featuresEnabled(): Boolean =
        !prefs.excludeTouchKeyboard || !softKeyboardVisible

    /** IME 창 높이 측정 재사용 버퍼(메인 스레드 전용 — windows 콜백/이벤트가 모두 메인). */
    private val imeBoundsBuf = android.graphics.Rect()

    /**
     * 접근성 윈도우에 "실제 화면 터치 키보드(IME 입력뷰)"가 떠 있으면 표시 중으로 본다.
     *
     * ⚠️ 단순히 `TYPE_INPUT_METHOD` 창의 **존재**만으로 판정하면 안 된다. 외장 키보드 사용자가
     * '하드웨어 키보드 툴바'(클립보드/추천 strip)를 켜 두면 이 **얇은 툴바도 TYPE_INPUT_METHOD 창**이라
     * 외장 키보드로 입력 중인데도 "터치 키보드 켜짐"으로 오인해 기능 전체가 꺼졌다(사용자 보고 버그).
     * 그래서 IME 창의 **면적**(화면 전체 대비 비율 [IME_KEYBOARD_MIN_SCREEN_AREA_FRACTION] 이상)으로
     * 구분한다 — 툴바는 얇은 띠(한 자릿수 %), 실제 키보드는 플로팅/분리형이어도 면적이 크다.
     */
    private fun computeSoftKeyboardVisible(): Boolean = runCatching {
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        if (screenH <= 0 || screenW <= 0) return@runCatching false
        val minAreaPx = screenH.toLong() * screenW.toLong() * IME_KEYBOARD_MIN_SCREEN_AREA_FRACTION
        val windowList = windows
        try {
            windowList.any { w ->
                if (w?.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
                w.getBoundsInScreen(imeBoundsBuf)
                imeBoundsBuf.width().toLong() * imeBoundsBuf.height().toLong() >= minAreaPx
            }
        } finally {
            // AccessibilityWindowInfo 도 API 33 미만에서 recycle 대상(누수 방지).
            windowList.forEach { runCatching { it?.recycle() } }
        }
    }.getOrDefault(false)

    /**
     * 소프트 키보드 표시 상태를 갱신하고, 바뀌면 배지 표시를 다시 평가.
     * 기능 OFF(기본)면 비싼 windows 순회를 하지 않아 일반 사용자에겐 부하가 없다.
     */
    private fun refreshSoftKeyboardState() {
        if (!initialized) return
        if (!prefs.excludeTouchKeyboard) {
            if (softKeyboardVisible) { softKeyboardVisible = false; refreshBadge() }
            return
        }
        val now = computeSoftKeyboardVisible()
        if (now != softKeyboardVisible) {
            softKeyboardVisible = now
            refreshBadge()
        }
    }

    /**
     * 윈도우 변경 폭주를 흡수하는 디바운스 재평가. 옵션 OFF 면 예약조차 하지 않는다.
     * `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOWS_CHANGED` 양쪽 다 이 경로를 탄다 — 과거엔 전자가
     * 디바운스 없이 매번 전체 윈도우 IPC 를 돌았다.
     */
    private fun scheduleSoftKeyboardRecheck() {
        if (!prefs.excludeTouchKeyboard) return
        if (softKeyboardRecheckPending) return
        softKeyboardRecheckPending = true
        mainHandler.postDelayed(softKeyboardRecheck, WINDOWS_CHANGED_DEBOUNCE_MS)
    }

    /** 외장 키보드 연결/해제 시: 소프트 키보드 표시 상태가 함께 바뀌므로 재평가(추가 기능 2). */
    private fun onKeyboardPresenceChanged() {
        if (!initialized) return
        guarded("keyboardPresence") { refreshSoftKeyboardState() }
    }

    // ---------------------------------------------------------------------
    // 배지 / 메뉴
    // ---------------------------------------------------------------------

    /** 배지 표시/숨김을 배지 설정과 기능 활성 여부에 따라 일관되게 반영. */
    private fun refreshBadge() {
        if (prefs.badgeEnabled && featuresEnabled()) overlay.showBadge(currentLang)
        else overlay.hideBadge()
    }

    /**
     * 배지가 떠 있어야 하는데 창 추가에 실패한 상태면 다시 시도한다.
     *
     * 접근성을 먼저 켜고 오버레이 권한을 나중에 준 경우, 최초 [refreshBadge] 의 addView 가
     * 권한 없음으로 조용히 실패하고 이후 배지를 다시 붙일 트리거가 없어 "한/영을 실제로 전환하거나
     * 회전하기 전까지 배지가 영영 안 뜨는" 상태가 된다. 창 전환 때마다 값싼 확인(플래그 비교)만
     * 하고, 실제로 빠졌을 때만 재시도한다.
     */
    private fun retryBadgeIfMissing() {
        if (!prefs.badgeEnabled || !featuresEnabled()) return
        if (overlay.hasBadge()) return
        // 권한이 계속 없으면 창 전환마다 addView 가 실패하므로 재시도 간격을 둔다.
        val now = SystemClock.uptimeMillis()
        if (now - lastBadgeRetryAt < BADGE_RETRY_INTERVAL_MS) return
        lastBadgeRetryAt = now
        refreshBadge()
    }

    /**
     * 배지 탭 시 뜨는 비눗방울 래디얼 메뉴 항목. 앱/설정 열기 + 주요 기능 즉석 토글.
     * 토글 값은 탭 시점에 prefs 에서 읽으므로 한 번만 구성해도 항상 현재 상태로 동작한다.
     */
    private fun buildQuickMenuItems(): List<QuickMenuItem> = listOf(
        QuickMenuItem(getString(R.string.quick_app)) { launchActivity(MainActivity::class.java) },
        QuickMenuItem(getString(R.string.quick_settings)) { launchActivity(SettingsActivity::class.java) },
        QuickMenuItem(getString(R.string.quick_flash)) {
            toggle(R.string.quick_flash, prefs.flashEnabled) { prefs.flashEnabled = it }
        },
        QuickMenuItem(getString(R.string.quick_replace)) {
            toggle(R.string.quick_replace, prefs.replaceEnabled) { prefs.replaceEnabled = it }
        },
        QuickMenuItem(getString(R.string.quick_badge)) {
            prefs.badgeEnabled = false
            // 설정 리스너 타이밍과 무관하게 즉시 배지를 숨긴다(이중 안전 — 숨기기 직후 미반영 방지).
            refreshBadge()
            toastMsg(getString(R.string.quick_badge_hidden))
        }
    )

    private fun launchActivity(cls: Class<*>) {
        runCatching {
            startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            // 백그라운드 액티비티 실행 제한 등으로 실패하면 아무 반응이 없어 "메뉴가 먹통"으로
            // 보인다. 최소한 실패를 알린다.
            toastMsg(getString(R.string.quick_launch_failed))
        }
    }

    /** 기능 ON/OFF 토글 + 새 상태를 토스트로 안내. */
    private fun toggle(labelRes: Int, current: Boolean, set: (Boolean) -> Unit) {
        val next = !current
        set(next)
        val state = getString(if (next) R.string.quick_state_on else R.string.quick_state_off)
        toastMsg("${getString(labelRes)} $state")
    }

    private fun toastMsg(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun onLanguageChanged(lang: String) {
        currentLang = lang
        // 터치 키보드 제외 ON + 소프트 키보드 표시 중 → 플래시/배지 모두 비활성(추가 기능 2).
        if (!featuresEnabled()) return
        overlay.showFlash(lang)
        overlay.updateBadge(lang)
    }

    // ---------------------------------------------------------------------
    // 접근성 이벤트
    // ---------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!initialized) return
        guarded("onAccessibilityEvent") {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    imeDetector.onWindowStateChanged(event)
                    scheduleSoftKeyboardRecheck()
                    retryBadgeIfMissing()
                }
                // 소프트 키보드(IME) 창의 등장/소멸은 주로 이 이벤트로 온다(옵션 ON 일 때만 구독).
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleSoftKeyboardRecheck()
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelectionChanged(event)
                // 글자가 실제 편집칸에 들어갔다는 가장 직접적인 신호(포커스 경고 오발동 방지용).
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val now = SystemClock.uptimeMillis()
                    if (shouldCheckEditable(now)) {
                        val node = runCatching { event.source }.getOrNull() ?: return@guarded
                        try {
                            markEditableActivity(node, now)
                        } finally {
                            runCatching { node.recycle() }
                        }
                    }
                }
                // 그 외 이벤트 타입은 구독하지 않으므로 명시적으로 무시(SwitchIntDef 경고 해소).
                else -> {}
            }
        }
    }

    /**
     * 선택 변경: 입력 실착 확인과 한영타 판정이 **같은 소스 노드**를 쓴다(노드 IPC 이벤트당 1회).
     * 한영타로 판정되면 [TextSelectionMonitor] 가 노드 소유권을 칩으로 넘기므로 여기서 recycle 하지 않는다.
     */
    private fun onSelectionChanged(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        val needMark = shouldCheckEditable(now)
        // 터치 키보드 제외 ON + 소프트 키보드 표시 중 → 한영타 교체 비활성(추가 기능 2).
        val needSel = prefs.replaceEnabled && featuresEnabled()
        if (!needMark && !needSel) return
        val node = runCatching { event.source }.getOrNull() ?: return
        var transferred = false
        try {
            if (needMark) markEditableActivity(node, now)
            if (needSel) transferred = selectionMonitor.onSelectionChanged(node)
        } finally {
            if (!transferred) runCatching { node.recycle() }
        }
    }

    /**
     * 편집 여부 확인(노드 IPC)이 필요한지. 포커스 경고가 꺼져 있으면 노드 접근조차 하지 않고,
     * 켜져 있어도 [EDIT_RECHECK_MS] 안에 이미 확인했으면 생략한다 — 결과와 무관하게 시각을 갱신해
     * 비편집 TextView 스트림에서도 IPC 가 300ms 당 1회로 상한된다.
     */
    private fun shouldCheckEditable(now: Long): Boolean {
        if (!prefs.noFocusEnabled) return false
        if (now - lastEditCheckAt < EDIT_RECHECK_MS) return false
        lastEditCheckAt = now
        return true
    }

    /** 소스 노드가 편집 가능하면 "최근 입력 실착" 시각을 갱신(텍스트 내용은 읽지 않음). */
    private fun markEditableActivity(node: AccessibilityNodeInfo, now: Long) {
        if (runCatching { node.isEditable }.getOrDefault(false)) lastEditableActivityAt = now
    }

    // ---------------------------------------------------------------------
    // 키 이벤트 (Feature 3)
    // ---------------------------------------------------------------------

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val e = event ?: return false
        if (!initialized) return false
        guarded("onKeyEvent") {
            // (Bug 1) 메인(디스패치) 스레드에서는 키 이벤트 속성만 보는 저비용 판정만 동기로 하고 즉시
            // 반환한다. 무거운 포커스 조회(노드 트리 IPC)는 백그라운드 스레드로 넘긴다.
            if (!keyMonitor.isTypingCandidate(e)) return false
            // 터치 키보드 제외 ON + 소프트 키보드 표시 중 → 포커스 없는 키 입력 경고 비활성(추가 기능 2).
            if (!featuresEnabled()) return false
            // 최근 입력 실착 여부는 메인 스레드에서 저렴하게 스냅샷(타임스탬프 비교)해 백그라운드로 전달.
            val recent = SystemClock.uptimeMillis() - lastEditableActivityAt < RECENT_INPUT_MS
            keyEvalHandler?.post {
                guarded("keyEval") { keyMonitor.handleCandidate(recentEditableActivity = recent) }
            }
        }
        return false // 키 이벤트는 절대 소비하지 않는다
    }

    /**
     * 1차(저비용): 활성 윈도우의 편집 가능한 입력 포커스만 확인. 평가 스레드(HandlerThread)에서
     * 돌므로 여기서 새는 예외는 곧 프로세스 사망이다 — 노드 접근/회수 전부 보호한다.
     */
    private fun hasActiveEditableFocus(): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        var focus: AccessibilityNodeInfo? = null
        return try {
            focus = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            runCatching { focus?.isEditable == true }.getOrDefault(false)
        } finally {
            // findFocus 가 루트 자신을 돌려주는 경우 이중 recycle 방지.
            if (focus != null && focus !== root) runCatching { focus.recycle() }
            runCatching { root.recycle() }
        }
    }

    /**
     * 2차(백업): 전체 윈도우를 순회해 편집 포커스를 탐색(멀티윈도우/IME 분리 대응).
     * 활성 윈도우가 잠시 비어있을 때 일시적 null 오판을 막기 위한 것으로, 1차가 실패할 때만 호출된다.
     * 회차마다 윈도우/루트/포커스 노드를 try/finally 로 회수해, 중간 예외에도 남은 객체가 누수되지 않는다.
     */
    private fun hasAnyEditableFocus(): Boolean {
        val list = runCatching { windows }.getOrNull() ?: return false
        var found = false
        for (w in list) {
            val win = w ?: continue
            try {
                if (found) continue
                val root = runCatching { win.root }.getOrNull() ?: continue
                try {
                    val node = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                        ?: continue
                    try {
                        if (runCatching { node.isEditable }.getOrDefault(false)) found = true
                    } finally {
                        if (node !== root) runCatching { node.recycle() }
                    }
                } finally {
                    runCatching { root.recycle() }
                }
            } finally {
                runCatching { win.recycle() }
            }
        }
        return found
    }

    override fun onInterrupt() { /* no-op */ }

    // ---------------------------------------------------------------------
    // 기타 시스템 콜백
    // ---------------------------------------------------------------------

    /**
     * 외장 키보드 연결/해제는 보통 [HardwareKeyboardDetector] 의 InputDeviceListener 가 잡지만,
     * 도킹/구성 변경 등 일부 경로에서 키보드 구성이 바뀔 수 있어 백스톱으로 한 번 더 확인한다(추가 기능 2).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!initialized) return
        guarded("onConfigurationChanged") {
            keyboardDetector?.recheck()
            refreshSoftKeyboardState()
            // 회전/화면 크기 변경으로 배지가 새 화면 밖에 남지 않게 보정(화면 밖이면 복구 불가).
            overlay.onScreenChanged()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!initialized) return
        // 메모리 압박이면 유휴 캐시(래디얼 메뉴 WebView)를 먼저 내놓는다.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            guarded("onTrimMemory") { overlay.trimQuickMenuCache() }
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (!initialized) return
        guarded("onSharedPreferenceChanged") {
            when (key) {
                Prefs.KEY_BADGE_ENABLED -> refreshBadge()
                Prefs.KEY_NOFOCUS_ENABLED -> {
                    syncServiceInfo()
                    syncKeyEvalThread()
                }
                Prefs.KEY_REPLACE_ENABLED -> syncServiceInfo()
                // "터치 키보드 제외"를 켜면 현재 소프트 키보드 표시 상태를 즉시 계산해 반영(추가 기능 2).
                Prefs.KEY_EXCLUDE_TOUCH_KEYBOARD -> {
                    syncServiceInfo()
                    syncKeyboardDetector()
                    refreshSoftKeyboardState()
                    refreshBadge()
                    // 윈도우 추적 플래그를 막 켠 직후엔 시스템이 창 목록을 비동기로 채우므로 한 번 더.
                    scheduleSoftKeyboardRecheck()
                }
                // 배지 크기/색은 표시 중인 배지에 즉시 재적용(꺼져 있으면 다음 표시 때 반영).
                Prefs.KEY_BADGE_SIZE, Prefs.KEY_BADGE_BG_COLOR, Prefs.KEY_BADGE_TEXT_COLOR -> {
                    if (prefs.badgeEnabled && featuresEnabled()) overlay.updateBadge(currentLang)
                }
                // 그 외 설정(플래시 색/속도/횟수, 임계값 등)은 사용 시점에 prefs 에서 즉시 읽으므로 별도 처리 불필요.
            }
        }
    }

    companion object {
        private const val TAG = "kIkI"

        /** 초기화 실패 시 재시도 간격(ms)과 최대 횟수(연결당). */
        private const val INIT_RETRY_MS = 2000L
        private const val INIT_MAX_RETRY = 3

        /**
         * 같은 타입 이벤트를 합쳐 전달하는 최소 간격(ms). XML 의 50 → 100: 타입당 최대 전달률이
         * 초당 20건에서 10건으로 준다. 언어 전환 감지는 이미 150ms 합치기 창이 있어 체감 무변화.
         */
        private const val NOTIFICATION_TIMEOUT_MS = 100L

        /**
         * 직전 입력 실착으로 "포커스 있음"을 인정하는 시간(ms). 타이핑 중 편집 이벤트 간격을 넉넉히
         * 덮어 오경고를 막되, 입력칸을 떠난 뒤에는 곧 경고가 정상 동작하도록 너무 길지 않게 둔다.
         */
        private const val RECENT_INPUT_MS = 1200L

        /**
         * (Bug 1) 편집 활동 재확인 간격(ms). 이 시간 안에 이미 확인했으면 글자마다 다시
         * `isEditable`(노드 IPC)을 보지 않는다. [RECENT_INPUT_MS] 보다 짧아 "최근 입력 실착" 판정은
         * 그대로 유지하면서 연타 시 IPC 폭주만 줄인다.
         */
        private const val EDIT_RECHECK_MS = 300L

        /** 포커스 조회 결과 캐시 시간(ms). 연타 시 키당 노드 IPC 를 초당 ~7회로 상한. */
        private const val FOCUS_PROBE_CACHE_MS = 150L

        /**
         * IME 창을 '실제 화면 터치 키보드'로 인정하는 최소 면적(화면 전체 면적 대비 비율).
         * 외장 키보드 툴바(전체 폭 × 얇은 높이, 면적 비율 한 자릿수 %)와 실제 터치 키보드(One UI
         * 플로팅/분리형처럼 작아도 가로·세로 모두 상당 부분을 차지해 면적 비율은 15%+) 사이에
         * 여백을 두어 가른다.
         */
        private const val IME_KEYBOARD_MIN_SCREEN_AREA_FRACTION = 0.1f

        /**
         * 윈도우 변경 재평가 디바운스(ms). 이 이벤트는 시스템 전역의 창 생성/소멸마다(팝업·토스트·
         * 다이얼로그·알림 shade …) 오므로, 매번 전체 윈도우 IPC 를 메인 스레드에서 돌면 "터치 키보드
         * 제외" 옵션 사용자에게 체감 지연이 생긴다.
         */
        private const val WINDOWS_CHANGED_DEBOUNCE_MS = 150L

        /**
         * 배지 창 재부착 재시도 최소 간격(ms). 오버레이 권한을 접근성보다 나중에 준 경우를
         * 스스로 복구하되, 권한이 끝내 없을 때 창 전환마다 실패 호출을 반복하지 않게 한다.
         */
        private const val BADGE_RETRY_INTERVAL_MS = 2000L
    }
}
