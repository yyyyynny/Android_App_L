package com.langsense.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.langsense.app.R
import com.langsense.app.util.ImeLocaleParser
import com.langsense.app.util.Prefs
import kotlin.math.roundToInt

/**
 * 모든 오버레이(WindowManager) 추가/제거를 담당하는 단일 래퍼.
 * 메인 스레드에서만 윈도우를 조작한다.
 */
class OverlayManager(private val context: Context, private val prefs: Prefs) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var flashView: FlashOverlayView? = null
    /** 플래시 창 파라미터(1회 생성 후 재사용). */
    private var flashParams: WindowManager.LayoutParams? = null
    /** 재생 종료 후 창을 잠시 유지했다가 제거하는 예약(연속 전환 시 창 생성/파괴 churn 방지). */
    private val flashLinger = Runnable { removeFlash() }
    // 같은 색 플래시가 아주 짧은 간격으로 다시 들어오면(잔존 중복 발화) 건너뛰는 렌더 단계 안전망.
    // 근본 원인은 ImeStateDetector(합치기/불응기/멱등)에서 처리하며, 이건 마지막 시각 방어일 뿐이다.
    private var lastFlashColorArgb = 0
    private var lastFlashAt = 0L
    /** 마지막 플래시의 실제 총 재생 길이(ms). 동일 색 중복 억제 창을 재생 길이에 연동하기 위함. */
    private var lastFlashTotalMs = 0L
    private var badgeView: BadgeOverlayView? = null
    private var chipView: ReplaceChipView? = null
    private var chipDismiss: Runnable? = null
    // 칩이 들고 있는 동안 소유하는 노드. 탭으로 소비되든, 타임아웃/교체로 버려지든 removeChip() 에서
    // 반드시 한 번 recycle() 한다(API 33 미만 노드 풀 누수 방지).
    private var pendingNode: AccessibilityNodeInfo? = null
    // 현재 떠 있는 칩의 (변환 결과, 선택 범위). 드래그 중 동일 칩 재생성(깜박임/타이머 리셋) 억제용.
    private var lastChipConverted: String? = null
    private var lastChipSelStart = -1
    private var lastChipSelEnd = -1

    private var quickMenuView: QuickMenuOverlayView? = null
    /** 간편 메뉴 항목(앱 열기/설정/토글 등). 서비스가 [setQuickMenuItems] 로 주입. */
    private var quickMenuItems: List<QuickMenuItem> = emptyList()
    /** 닫힌 메뉴(WebView)의 유휴 캐시. [QUICK_MENU_CACHE_MS] 뒤 또는 메모리 압박 시 폐기. */
    private var cachedQuickMenu: QuickMenuOverlayView? = null
    private val quickMenuCacheExpire = Runnable { trimQuickMenuCache() }
    /** 퀵메뉴 창 파라미터(1회 생성 후 재사용). */
    private var quickMenuParams: WindowManager.LayoutParams? = null

    private val overlayType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * 정리(removeAll) 후 도착하는 늦은 콜백이 오버레이를 다시 추가하지 못하게 막는 플래그 (Bug 4 감사).
     * 버그 1 수정으로 백그라운드 키 평가 스레드 → onWarn → onMain 경로가 생겨, 서비스 정리 직후
     * 큐에 남아 있던 post 가 실행되며 뷰를 재생성해 누수될 수 있다. removeAll 에서 동기로 set 하고
     * 모든 표시 진입점에서 확인한다. (재연결 시 서비스가 OverlayManager 를 새로 만들므로 상태 오염 없음)
     */
    @Volatile
    private var released = false

    /** 마지막 "언어 전환" 플래시 시각(uptime). 색과 무관한 에피소드 가드용(아래 showFlash 참조). */
    private var lastLangFlashAt = 0L

    // ---------------------------------------------------------------------
    // Feature 1 / 3: 플래시
    // ---------------------------------------------------------------------

    /** 언어 전환 플래시. 설정이 꺼져있거나 해당 언어가 비활성이면 무시. */
    fun showFlash(lang: String) = onMain {
        if (released) return@onMain
        if (!prefs.flashEnabled) return@onMain
        // 언어 미판별(UNKNOWN)은 사용자에게 "UNKNOWN" 텍스트 플래시로 노출하지 않는다.
        if (lang == ImeLocaleParser.UNKNOWN) return@onMain
        if (!prefs.isLangEnabled(lang)) return@onMain
        // 렌더 단계 안전망 두 겹. 근본 방어는 ImeStateDetector 이며 여기는 최종 시각 방어일 뿐이다.
        // ① 색과 무관한 에피소드 최소 간격(LANG_FLASH_MIN_INTERVAL_MS=300ms) — 같은 전환에서
        //    비롯된 즉발 중복 억제. 과거 700ms 는 빠른 정상 재전환까지 삼켜 "씹힘"의 원인이었다.
        // ② 동일 색 중복 억제 창 = "직전 재생의 실제 총 길이 + 꼬리 여유(FLASH_DEDUP_MS)".
        //    시작 시각 기준 고정 350ms 는 짧은 설정(100ms×1회=총 150ms)에서 잔존 중복(~350~550ms
        //    후 도착)이 창 만료 직후 별개 깜박임으로 렌더되는 원인이었다.
        // 두 검사 모두 통과했을 때만 상태를 갱신한다 — 억제된 요청이 창을 앞으로 밀어
        // 정당한 다음 플래시까지 삼키지 않게 하기 위함.
        val now = SystemClock.uptimeMillis()
        if (now - lastLangFlashAt < LANG_FLASH_MIN_INTERVAL_MS) {
            Log.d(TAG, "flash suppressed(episode): lang=$lang, +${now - lastLangFlashAt}ms")
            return@onMain
        }
        val colorArgb = prefs.flashColorArgb(lang)
        if (colorArgb == lastFlashColorArgb && now - lastFlashAt < lastFlashTotalMs + FLASH_DEDUP_MS) {
            Log.d(TAG, "flash suppressed(dedup): lang=$lang, +${now - lastFlashAt}ms")
            return@onMain
        }
        lastLangFlashAt = now
        lastFlashColorArgb = colorArgb
        lastFlashAt = now
        lastFlashTotalMs = FlashOverlayView.totalDurationMs(prefs.flashDurationMs, prefs.flashCount)
        Log.d(TAG, "flash render: lang=$lang, total=${lastFlashTotalMs}ms")
        flash(colorArgb, ImeLocaleParser.displayName(lang))
    }

    /**
     * 포커스 없는 키 입력 경고(동일한 플래시 방식, 회색 + 안내 텍스트).
     * 재경고 쿨다운은 KeyEventMonitor 가 담당하므로 여기서는 바로 렌더하되, 언어 플래시의
     * dedup 상태(lastFlashColorArgb 등)는 건드리지 않는다 — 과거엔 회색이 그 상태를 오염시켜
     * 직후 언어 플래시의 중복 억제를 무력화했다.
     */
    fun showNoFocusWarning(message: String) = onMain {
        if (released) return@onMain
        Log.d(TAG, "flash render: no-focus warning")
        flash(0xD9555555.toInt(), message)
    }

    /**
     * 순수 렌더 — 중복 억제/상태 갱신은 호출자(showFlash) 책임.
     *
     * 저사양 최적화: 전체화면 창(2000×1200 서피스 ≈ 9.6MB×버퍼)을 플래시마다 만들고 부수면 전환마다
     * 30~80ms 가 든다. 뷰/창을 1개만 유지하고, 재생이 끝나면 곧바로 제거하는 대신 INVISIBLE 로 숨긴 채
     * [FLASH_LINGER_MS] 동안 붙여 둔다 — 그 사이 다음 전환이 오면 addView 없이 `play()` 만 다시 한다.
     * (INVISIBLE 이면 WMS 가 창을 합성/입력에서 제외하므로 알파 0 으로 두는 것과 달리 상주 비용이 없다.)
     */
    private fun flash(colorArgb: Int, text: String) {
        handler.removeCallbacks(flashLinger)
        var view = flashView
        if (view == null) {
            view = FlashOverlayView(context)
            val params = flashParams ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).also {
                // [7] 기본 윈도우 enter/exit 애니메이션 제거 → 색이 좌→우로 채워지지 않고 전체 화면이
                // 한 프레임에 꽉 찬 상태로 나타난다. (페이드아웃은 뷰 알파 애니메이션이 따로 처리)
                it.windowAnimations = 0
                flashParams = it
            }
            if (runCatching { wm.addView(view, params) }.isFailure) return
            flashView = view
        } else {
            view.cancel() // 진행 중이면 절단(기존 "교체" 의미 그대로)
            view.visibility = View.VISIBLE
        }
        val v = view
        // onEnd 시점에 다른 뷰로 교체되었을 수 있으므로 자기 자신일 때만 처리(오제거 방지).
        v.play(colorArgb, text, prefs.flashDurationMs, prefs.flashCount) { onFlashEnded(v) }
    }

    private fun onFlashEnded(view: FlashOverlayView) {
        if (flashView !== view) return
        view.visibility = View.INVISIBLE
        handler.postDelayed(flashLinger, FLASH_LINGER_MS)
    }

    private fun removeFlash() {
        handler.removeCallbacks(flashLinger)
        flashView?.let {
            it.cancel()
            runCatching { wm.removeView(it) }
        }
        flashView = null
    }

    // ---------------------------------------------------------------------
    // Feature 2: 배지
    // ---------------------------------------------------------------------

    fun showBadge(lang: String) = onMain {
        if (released) return@onMain
        if (!prefs.badgeEnabled) {
            hideBadgeInternal()
            return@onMain
        }
        val label = ImeLocaleParser.badgeLabel(lang)
        val existing = badgeView
        if (existing == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            val view = BadgeOverlayView(
                context, wm, params,
                onTap = { toggleQuickMenu() },
                onPositionSaved = { x, y -> prefs.setBadgePosition(x, y) }
            )
            badgeStyleSig = null // 새 뷰에는 반드시 적용
            applyBadgeStyle(view)
            view.setLanguage(label)

            // 저장 위치가 있으면 사용, 없으면 우하단 기본값
            if (prefs.badgeX >= 0 && prefs.badgeY >= 0) {
                // 저장 시점과 화면 방향/크기가 달라졌을 수 있으므로(회전 후 재표시 등) 현재 화면
                // 경계로 1차 보정 — 이대로면 배지가 화면 밖에 붙어 드래그로도 복구할 수 없다.
                val dm = context.resources.displayMetrics
                params.x = prefs.badgeX.coerceIn(0, dm.widthPixels)
                params.y = prefs.badgeY.coerceIn(0, dm.heightPixels)
            } else {
                val (dx, dy) = BadgeOverlayView.defaultPosition(view)
                params.x = dx
                params.y = dy
            }
            runCatching { wm.addView(view, params) }.onFailure { return@onMain }
            badgeView = view
            // 실측 크기(view.width)는 첫 레이아웃 후에야 알 수 있으므로, 붙은 뒤 배지 폭까지
            // 반영한 2차 보정으로 완전히 화면 안에 들어오게 한다.
            view.post { view.ensureOnScreen() }
        } else {
            // 재사용: 라벨 + 크기/색 스타일을 다시 적용(설정 변경 즉시 반영).
            applyBadgeStyle(existing)
            existing.setLanguage(label)
        }
    }

    /** 마지막으로 배지에 적용한 (크기, 배경, 글씨) — 같으면 재적용을 건너뛴다. null = 미적용. */
    private var badgeStyleSig: Triple<Int, Int, Int>? = null

    /**
     * 현재 설정(크기/배경색/글씨색)을 배지에 적용. 언어 전환마다 [showBadge] 가 호출되는데, 스타일이
     * 그대로인데도 매번 GradientDrawable 3 + LayerDrawable 1 을 새로 만들고 재레이아웃하던 것을
     * 시그니처 비교로 건너뛴다(설정 변경 시엔 값이 달라 자연히 재적용).
     */
    private fun applyBadgeStyle(view: BadgeOverlayView) {
        val sig = Triple(prefs.badgeSize, prefs.badgeBgColorArgb(), prefs.badgeTextColorArgb())
        if (sig == badgeStyleSig) return
        badgeStyleSig = sig
        view.applyStyle(sig.first, sig.second, sig.third)
    }

    fun updateBadge(lang: String) = showBadge(lang)

    /** 배지 창이 실제로 붙어 있는지(오버레이 권한이 늦게 부여된 경우의 재시도 판단용). */
    fun hasBadge(): Boolean = badgeView != null

    /**
     * 화면 회전/크기 변경 시 서비스가 호출. 새 화면 밖에 남은 배지를 화면 안으로 되돌린다
     * (배지 창은 서비스 생존 동안 유지되므로 여기서 보정하지 않으면 배지가 사라진 것처럼 보이고
     * 화면 밖이라 드래그로도 복구 불가). post 로 미뤄 새 displayMetrics 가 반영된 뒤 실행한다.
     */
    fun onScreenChanged() = onMain {
        if (released) return@onMain
        val v = badgeView ?: return@onMain
        v.post {
            // 저장된 원래 위치에서 다시 클램프한다. 현재 좌표만 클램프하면 세로→가로에서 눌린
            // 값이 세로로 돌아와도 그대로 남아(유효 범위라 보정 안 됨) 배지가 영구히 이동한다.
            if (prefs.badgeX >= 0 && prefs.badgeY >= 0) v.moveWithinScreen(prefs.badgeX, prefs.badgeY)
            else v.ensureOnScreen()
        }
    }

    fun hideBadge() = onMain { hideBadgeInternal() }

    private fun hideBadgeInternal() {
        hideQuickMenuInternal() // 배지가 사라지면 그 주위 메뉴도 함께 닫는다
        badgeView?.let { runCatching { wm.removeView(it) } }
        badgeView = null
        badgeStyleSig = null
    }

    // ---------------------------------------------------------------------
    // 간편 메뉴(비눗방울 래디얼) — 배지 탭으로 열림
    // ---------------------------------------------------------------------

    /** 서비스가 간편 메뉴 항목을 주입(앱 열기/설정/기능 토글 등). */
    fun setQuickMenuItems(items: List<QuickMenuItem>) {
        quickMenuItems = items
    }

    /** 배지 탭 시: 열려 있으면 (수납 애니메이션과 함께) 닫고, 닫혀 있으면 배지를 앵커로 메뉴를 연다. */
    fun toggleQuickMenu() = onMain {
        if (released) return@onMain
        quickMenuView?.let {
            badgeView?.pulse()
            it.requestCollapse() // 수납 애니메이션 후 onDismiss 콜백이 윈도우를 제거한다
            return@onMain
        }
        val bv = badgeView ?: return@onMain
        if (quickMenuItems.isEmpty()) return@onMain
        bv.pulse()
        // 배지가 사라지는 일은 없지만(사라지면 hideQuickMenuInternal 이 메뉴도 함께 닫음) 방어적으로
        // 최초 좌표를 폴백으로 남겨둔다.
        val initialAnchor = badgeCenterOnScreen(bv)
        val reduce = prefs.radialReduceMotion

        // 저사양 최적화: 직전에 닫힌 메뉴(WebView)가 캐시에 살아 있으면 재사용한다 — WebView 콜드
        // 스타트(저사양 0.5~1.5s, 40~80MB churn)를 두 번째 오픈부터 피한다. 렌더러가 죽었거나
        // 저사양 모드 값이 바뀌었으면(HTML 초기화가 달라짐) 버리고 새로 만든다.
        handler.removeCallbacks(quickMenuCacheExpire)
        val cached = cachedQuickMenu
        cachedQuickMenu = null
        val reused = cached != null && !cached.isDead && cached.reduceMotion == reduce
        if (cached != null && !reused) cached.destroyNow()

        val view = if (reused) cached!! else runCatching {
            // 생성자에서 WebView 를 만드는데, WebView 프로바이더가 업데이트 중이거나 비활성화면
            // RuntimeException 이 던져진다. 이 경로는 배지 터치 리스너에서 바로 이어지므로 감싸지
            // 않으면 프로세스가 죽고 시스템이 접근성 서비스를 꺼버린다(사용자가 수동 재활성화 필요).
            QuickMenuOverlayView(
                context,
                anchorProvider = {
                    // 회전 등으로 다시 레이아웃될 때 배지의 "현재" 위치를 읽는다(Bug 6 — 고정 좌표로
                    // 열면 회전 후 예전 배지 위치를 중심으로 부채꼴이 펼쳐지는 문제 방지).
                    badgeView?.let { badgeCenterOnScreen(it) } ?: initialAnchor
                },
                items = quickMenuItems,
                reduceMotion = reduce // 저사양 모드면 펼친 뒤 연속 애니메이션을 끈다
            ) {
                hideQuickMenu()
            }
        }.getOrElse {
            Log.w(TAG, "quick menu unavailable: ${it.javaClass.simpleName}")
            runCatching {
                Toast.makeText(context, R.string.quick_menu_unavailable, Toast.LENGTH_SHORT).show()
            }
            return@onMain
        }
        val params = quickMenuParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // 터치는 받되(스크림/버튼) 키 포커스는 안 가져간다. 전체 화면 모달.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also {
            it.windowAnimations = 0 // 등장 연출은 뷰 애니메이션으로 직접 처리
            quickMenuParams = it
        }
        if (runCatching { wm.addView(view, params) }.isFailure) {
            // attach 가 안 된 뷰는 onDetachedFromWindow 가 영영 오지 않아 WebView 가 누수된다.
            view.destroyNow()
            return@onMain
        }
        quickMenuView = view
        if (reused) view.reopen()
    }

    /** 닫힌 메뉴의 유휴 캐시 폐기(만료·메모리 압박·정리). 서비스의 onTrimMemory 에서도 호출. */
    fun trimQuickMenuCache(): Unit = onMain {
        handler.removeCallbacks(quickMenuCacheExpire)
        cachedQuickMenu?.destroyNow()
        cachedQuickMenu = null
    }

    /**
     * 배지 중심의 **화면 절대 좌표**(px). 배지 창은 FLAG_LAYOUT_IN_SCREEN 없이 추가되어
     * params.x/y 의 원점이 상태바 아래(콘텐츠 영역)인 반면, 퀵메뉴 창은 FLAG_LAYOUT_IN_SCREEN
     * 이라 (0,0)=화면 최상단이다. params 좌표를 그대로 넘기면 이 차이만큼(상태바 높이) 팬이
     * 배지보다 위에 붙으므로, 두 창 모두에서 유효한 화면 좌표로 읽는다.
     */
    private fun badgeCenterOnScreen(view: BadgeOverlayView): Pair<Int, Int> {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return (loc[0] + view.width / 2) to (loc[1] + view.height / 2)
    }

    fun hideQuickMenu() = onMain { hideQuickMenuInternal() }

    private fun hideQuickMenuInternal() {
        val v = quickMenuView ?: return
        quickMenuView = null
        runCatching { wm.removeView(v) }
        if (released || v.isDead) {
            v.destroyNow()
            return
        }
        // 창만 떼고 WebView 는 잠시 보관 — 다음 오픈이 콜드 스타트를 건너뛴다(toggleQuickMenu 참조).
        cachedQuickMenu?.destroyNow()
        cachedQuickMenu = v
        handler.removeCallbacks(quickMenuCacheExpire)
        handler.postDelayed(quickMenuCacheExpire, QUICK_MENU_CACHE_MS)
    }

    // ---------------------------------------------------------------------
    // Feature 4: "교체?" 칩
    // ---------------------------------------------------------------------

    /**
     * 한영타 교체 칩 표시.
     * @param node 선택이 일어난 편집 노드
     * @param fullText 노드 전체 텍스트
     * @param selStart 선택 시작
     * @param selEnd 선택 끝
     * @param converted 변환된 한국어
     */
    fun showReplaceChip(
        node: AccessibilityNodeInfo,
        fullText: String,
        selStart: Int,
        selEnd: Int,
        converted: String
    ) = onMain {
        if (released) { node.recycle(); return@onMain }
        // 드래그로 선택을 넓히는 동안 selection-changed 가 연발하는데, 매번 칩을 재생성하면
        // 칩이 깜박이고 2초 소멸 타이머도 계속 리셋돼 "마지막 이벤트 후 2초"까지 남는다.
        // 같은 변환 결과·같은 선택 범위면 떠 있는 칩(과 타이머)을 그대로 둔다.
        if (chipView != null && converted == lastChipConverted &&
            selStart == lastChipSelStart && selEnd == lastChipSelEnd
        ) {
            node.recycle()
            return@onMain
        }
        removeChip() // 이전 칩이 있었다면 그 노드까지 함께 회수
        pendingNode = node
        val view = ReplaceChipView(context)
        view.bind(converted) {
            applyReplacement(node, fullText, selStart, selEnd, converted)
            removeChip()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(8f)
        }
        // 실패 시 removeChip 으로 pendingNode 까지 회수 — 여기서 그냥 빠지면 칩도 타이머도 없어
        // 방금 세팅한 pendingNode 를 회수할 주체가 사라진다(노드 풀 누수).
        runCatching { wm.addView(view, params) }.onFailure { removeChip(); return@onMain }
        chipView = view
        lastChipConverted = converted
        lastChipSelStart = selStart
        lastChipSelEnd = selEnd

        chipDismiss = Runnable { removeChip() }.also { handler.postDelayed(it, CHIP_TIMEOUT_MS) }
    }

    /**
     * 선택 영역을 변환 결과로 교체.
     *
     * 1차로 [AccessibilityNodeInfo.ACTION_SET_TEXT] 로 전체 텍스트를 통째로 교체한다.
     * 삼성 노트·일부 WebView/특수 에디터는 ACTION_SET_TEXT 를 무시하거나 false 를 반환하므로
     * (Bug 3) 클립보드 + 선택영역 재설정 + 붙여넣기로 fallback 한다.
     */
    private fun applyReplacement(
        node: AccessibilityNodeInfo,
        fullText: String,
        selStart: Int,
        selEnd: Int,
        converted: String
    ) {
        val s = selStart.coerceIn(0, fullText.length)
        val e = selEnd.coerceIn(s, fullText.length)
        val original = fullText.substring(s, e)

        // 노드가 갱신되었을 수 있으므로 최신 텍스트를 우선 사용(없으면 캡처된 fullText).
        // 칩이 떠 있던 최대 2초 사이 창이 사라졌으면 노드가 stale 이라 접근 자체가 던질 수 있다.
        val liveText = runCatching { node.text?.toString() }.getOrNull() ?: fullText
        val (es, ee) = resolveReplaceRange(liveText, s, e, original) ?: return
        val newText = liveText.substring(0, es) + converted + liveText.substring(ee)

        if (trySetText(node, newText)) {
            // ACTION_SET_TEXT 는 Editable 전체를 갈아끼우므로 대부분의 에디터에서 커서가 문서
            // 끝으로 튄다. 교체한 자리 뒤로 되돌려 사용자가 이어서 타이핑할 수 있게 한다
            // (지원하지 않는 에디터도 있으므로 best-effort).
            val caret = es + converted.length
            val caretArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, caretArgs) }
            return
        }
        pasteFallback(node, converted, es, ee)
    }

    /**
     * "교체?" 칩이 떠 있는 최대 2초 동안 사용자가 앞쪽 텍스트를 편집하면 (s..e) 오프셋이 실제 대상과
     * 어긋난다. 그 자리에 원래 선택했던 문자열([original])이 그대로 있을 때만 그 위치를 신뢰하고,
     * 아니면 문서 안에서 같은 문자열을 다시 찾는다(첫 매치). 그마저 못 찾으면 엉뚱한 곳을 덮어쓰지
     * 않도록 교체를 포기한다(null).
     */
    private fun resolveReplaceRange(liveText: String, s: Int, e: Int, original: String): Pair<Int, Int>? {
        if (e <= liveText.length && liveText.substring(s, e) == original) return s to e
        val idx = liveText.indexOf(original)
        if (idx < 0) return null
        return idx to (idx + original.length)
    }

    /** 1차: ACTION_SET_TEXT. editable 이 아니거나 false 반환 시 실패로 간주. */
    private fun trySetText(node: AccessibilityNodeInfo, newText: String): Boolean {
        if (!runCatching { node.isEditable }.getOrDefault(false)) return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    /**
     * 2차(fallback): 클립보드에 변환 결과를 넣고, 교체 대상 영역을 선택한 뒤 붙여넣기.
     * 붙여넣기는 "현재 선택 영역을 대체"하므로 선택영역 재설정이 핵심이다.
     * 그래도 실패하면 클립보드에는 변환 결과가 남아 사용자가 직접 붙여넣을 수 있다.
     */
    private fun pasteFallback(node: AccessibilityNodeInfo, converted: String, s: Int, e: Int) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { cm?.setPrimaryClip(ClipData.newPlainText("langsense", converted)) }

        // 포커스 확보 → 교체 대상 영역 선택 → 붙여넣기
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e)
        }
        val selectionSet = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }.getOrDefault(false)

        // 선택이 안 잡혔으면 붙여넣기를 하면 안 된다 — "선택 영역 대체"가 아니라 현재 커서 위치에
        // 그냥 삽입돼, 원래 영타는 남고 그 옆에 한글이 하나 더 붙는다(텍스트 파손).
        val pasted = selectionSet && runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)

        if (!pasted) {
            // 붙여넣기까지 실패: 변환 결과가 클립보드에 있음을 사용자에게 안내.
            handler.post {
                runCatching {
                    Toast.makeText(context, R.string.replace_copied_fallback, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun removeChip() {
        chipDismiss?.let { handler.removeCallbacks(it) }
        chipDismiss = null
        chipView?.let { runCatching { wm.removeView(it) } }
        chipView = null
        pendingNode?.let { runCatching { it.recycle() } }
        pendingNode = null
        lastChipConverted = null
        lastChipSelStart = -1
        lastChipSelEnd = -1
    }

    // ---------------------------------------------------------------------
    // 정리
    // ---------------------------------------------------------------------

    fun removeAll() {
        // 늦은 콜백이 이후 뷰를 다시 추가하지 못하도록 동기로 먼저 막는다(Bug 4 감사).
        released = true
        onMain {
            removeFlash()
            hideQuickMenuInternal()
            trimQuickMenuCache()
            hideBadgeInternal()
            removeChip()
        }
    }

    // ---------------------------------------------------------------------
    // 헬퍼
    // ---------------------------------------------------------------------

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else handler.post(block)
    }

    private fun statusBarHeight(): Int {
        @Suppress("DiscouragedApi", "InternalInsetResource")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24f)
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        /** 플래시 렌더/억제 추적용(실기기에서 "추가 깜박임"이 트리거인지 렌더인지 판별). */
        private const val TAG = "OverlayManager"

        const val CHIP_TIMEOUT_MS = 2000L

        /**
         * 플래시 재생 종료 후 창을 (숨긴 채) 유지하는 시간(ms). 이 안에 다음 전환이 오면 창 생성/파괴
         * 없이 재생만 다시 한다. 연속 토글의 전형적 간격을 덮되 무한 상주는 피한다.
         */
        const val FLASH_LINGER_MS = 1500L

        /**
         * 닫힌 래디얼 메뉴(WebView)를 재사용을 위해 보관하는 시간(ms). 짧게 잡아 저사양 기기에서
         * 40~60MB 가 오래 상주하지 않게 하되, "닫았다가 바로 다시 여는" 흔한 패턴은 덮는다.
         */
        const val QUICK_MENU_CACHE_MS = 20_000L

        /**
         * 같은 색 플래시 중복 억제의 "재생 종료 후 꼬리 여유"(ms). 실제 억제 창은
         * 직전 재생의 총 길이([FlashOverlayView.totalDurationMs]) + 이 값 — flash() 참조.
         * 근본 방어(ImeStateDetector) 뒤의 렌더 단계 마지막 안전망.
         */
        const val FLASH_DEDUP_MS = 350L

        /**
         * 언어 전환 플래시의 "전환 에피소드" 최소 간격(ms). 색과 무관하게 이 간격 안의 추가 플래시는
         * 같은 전환의 중복으로 보고 건너뛴다. 중복의 근본 방어는 ImeStateDetector 의 권위 소스 읽기가
         * 담당하므로, 여기는 합치기 창(150ms)만 살짝 웃도는 최종 안전망 수준으로 짧게 잡는다 —
         * 과거 700ms 는 1초 안의 정상 재전환 플래시까지 삼켜 "반응 씹힘"을 만들었다.
         */
        const val LANG_FLASH_MIN_INTERVAL_MS = 300L
    }
}
