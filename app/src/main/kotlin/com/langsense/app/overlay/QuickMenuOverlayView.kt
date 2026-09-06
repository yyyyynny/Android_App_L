package com.langsense.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray

/** 간편 메뉴 항목(표시 라벨 + 탭 동작). */
data class QuickMenuItem(val label: String, val onClick: () -> Unit)

/**
 * 플로팅 래디얼 메뉴 (추가 기능 1).
 *
 * **사용자가 제공한 원본 HTML(`design/reference/radialmenu.html` 스냅샷)에 앱 통합 배선을 더한
 * `assets/radialmenu.html` 을 WebView 로 렌더링한다.** 과거의 네이티브(Canvas) 재해석은 원본과 미세하게
 * 달라 폐기했고, 이제 외형·모션은 그 HTML 파일이 진실이다(원본 대비 앱이 바꾼 것은 CLAUDE.md Feature 5 참조).
 *
 * 통합 방식:
 *  - 페이지 로드 후 [WebBridge] 를 통해 `KikiInit({anchorX,anchorY(dp), reduceMotion, labels})` 호출 →
 *    네이티브 배지 위치에 팬을 맞추고, 라벨을 주입하고, 저사양 모드를 반영한 뒤 자동으로 펼친다.
 *    JS 는 초기화가 끝나면 `KikiNative.onReady()` 로 ack 한다(워치독 기준).
 *  - 오브 탭 → JS 가 `KikiNative.onItemTap(i)` → 해당 [QuickMenuItem] 실행 후 수납.
 *  - 스크림(빈 곳) 탭 → JS 가 `KikiNative.onDismiss()` → 수납.
 *  - 배지 재탭( [requestCollapse] ) → JS `KikiCollapse()` 로 수납 애니메이션 후 창 제거.
 *
 * ### 재사용(저사양)
 * 창에서 떼어져도 WebView 를 파괴하지 않는다 — [OverlayManager] 가 잠시 보관했다가 다음 오픈 때
 * [reopen] 으로 다시 붙이면 콜드 스타트(저사양 0.5~1.5s) 없이 즉시 열린다. 실제 파괴는 [destroyNow]
 * (캐시 만료·메모리 압박·서비스 정리·렌더러 사망) 에서만.
 *
 * WebView 는 프레임워크(android.webkit)라 외부 의존성 추가가 없다(최소 의존성 원칙 준수).
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class QuickMenuOverlayView(
    context: Context,
    /** 앵커(배지 중심) 좌표(px)를 그때그때 조회. 회전 등으로 배지가 옮겨져도 최신값을 쓴다(Bug 6). */
    private val anchorProvider: () -> Pair<Int, Int>,
    private val items: List<QuickMenuItem>,
    /** 저사양(움직임 줄이기) 모드: 원본 HTML 의 연속 애니메이션(오브 morph/부유/별/먼지/선 sway)을 끈다. */
    val reduceMotion: Boolean,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissed = false
    private var destroyed = false

    /** HTML 이 KikiInit 을 끝내고 ack 했는지(워치독이 창을 닫을지 판단). */
    private var menuReady = false

    /** 렌더러 사망/로드 실패/파괴 — 재사용 불가. [OverlayManager] 가 캐시 대신 폐기한다. */
    var isDead = false
        private set

    private val watchdog = Runnable {
        if (!menuReady) {
            isDead = true // 초기화에 실패한 페이지는 재사용하지 않는다
            dismiss()
        }
    }

    private val webView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT) // 실제 화면 위에 오버레이 — 배경 투명
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        // 시스템 글꼴 크기(접근성 설정)가 오브 라벨을 밀어내지 않도록 배율 고정 — 오브 치수는
        // px 고정이라 확대되면 라벨이 오브 밖으로 넘치거나 서로 겹친다.
        settings.textZoom = 100
        addJavascriptInterface(WebBridge(), "KikiNative")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                initMenu()
            }

            // 로드 실패 시 창을 닫는다 — 이 창은 전체 화면을 덮고 터치를 소비하므로,
            // 열리지도 닫히지도 않으면 사용자가 화면을 되찾을 방법이 없다.
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != false) {
                    isDead = true
                    mainHandler.post { dismiss() }
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                // true 를 반환해야 앱이 함께 죽지 않는다(반환 안 하면 프로세스 종료).
                isDead = true
                mainHandler.post { dismiss() }
                return true
            }
        }
    }

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.loadUrl("file:///android_asset/radialmenu.html")
        // 워치독: 로드가 끝내 완료되지 않으면(에러 콜백조차 안 오는 경우 포함) 창을 닫는다.
        mainHandler.postDelayed(watchdog, LOAD_TIMEOUT_MS)
    }

    /** 로드 완료 후 배지 위치(dp)·라벨·저사양 여부를 HTML 로 주입하고 자동 펼침. */
    private fun initMenu() {
        if (dismissed || destroyed) return
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        val (ax, ay) = anchorProvider()
        val anchorXdp = ax / density
        val anchorYdp = ay / density
        val labelsJson = JSONArray(items.map { it.label }).toString()
        val cfg = "{anchorX:${anchorXdp}, anchorY:${anchorYdp}, " +
            "reduceMotion:${reduceMotion}, labels:${labelsJson}}"
        evalJs("window.KikiInit && window.KikiInit($cfg);")
    }

    /**
     * 캐시에서 꺼내 다시 붙인 뒤 호출: 페이지는 이미 로드돼 있으므로 KikiInit 만 다시 보낸다
     * (앵커/미러/틸트는 KikiInit 이 매번 다시 계산). 짧은 워치독으로 ack 를 기다린다.
     */
    fun reopen() {
        if (destroyed || isDead) return
        dismissed = false
        menuReady = false
        mainHandler.removeCallbacksAndMessages(null)
        initMenu()
        mainHandler.postDelayed(watchdog, REOPEN_TIMEOUT_MS)
    }

    /** 외부(배지 재탭/스크림)에서 우아한 수납 요청 — 수납 애니메이션 후 창 제거. */
    fun requestCollapse() {
        if (dismissed || destroyed) return
        evalJs("window.KikiCollapse && window.KikiCollapse();")
        mainHandler.postDelayed({ dismiss() }, COLLAPSE_REMOVE_MS)
    }

    /** WebView 를 완전히 파괴한다(재사용 불가). 여러 번 호출해도 안전. */
    fun destroyNow() {
        if (destroyed) return
        destroyed = true
        isDead = true
        dismissed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true
        onDismiss()
    }

    private fun evalJs(script: String) {
        if (destroyed) return
        runCatching { webView.evaluateJavascript(script, null) }
    }

    override fun onDetachedFromWindow() {
        // 외부 경로(배지 숨김 → hideQuickMenuInternal 등)로 창이 제거된 경우에도 이후의 늦은
        // 콜백이 닫힌 메뉴를 만지지 못하게 표시한다. WebView 는 파괴하지 않는다(재사용 캐시).
        dismissed = true
        mainHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /** JS→네이티브 브리지. JS 스레드에서 호출되므로 메인 스레드로 넘겨 처리한다. */
    private inner class WebBridge {
        @JavascriptInterface
        fun onReady() {
            mainHandler.post { menuReady = true }
        }

        @JavascriptInterface
        fun onItemTap(index: Int) {
            mainHandler.post {
                if (dismissed || destroyed) return@post
                // 항목 실행이 던져도 메뉴는 닫혀야 한다(전체 화면 터치를 소비하는 창이라 잠기면 안 됨).
                runCatching { items.getOrNull(index)?.onClick?.invoke() }
                // "배지 숨기기" 항목은 onClick 안에서 이 메뉴 창 제거까지 동기로 이어질 수 있다.
                if (dismissed || destroyed) return@post
                // 수납 애니메이션을 잠깐 보인 뒤 창 제거(항목 실행은 이미 완료).
                evalJs("window.KikiCollapse && window.KikiCollapse();")
                mainHandler.postDelayed({ dismiss() }, COLLAPSE_REMOVE_MS)
            }
        }

        @JavascriptInterface
        fun onDismiss() {
            mainHandler.post { requestCollapse() }
        }
    }

    companion object {
        /** HTML 수납 애니메이션(≈0.46s 트랜지션) 후 창을 제거하기까지의 지연(ms). */
        private const val COLLAPSE_REMOVE_MS = 320L

        /**
         * 로드 워치독(ms). 이 시간 안에 메뉴가 초기화(ack)되지 않으면 창을 제거한다 —
         * 전체 화면을 덮고 터치를 소비하는 창이라 열리지도 닫히지도 않으면 화면이 잠긴다.
         * 저사양 기기의 콜드 WebView 초기화(수백 ms~1초대)를 충분히 덮는 값.
         */
        private const val LOAD_TIMEOUT_MS = 3000L

        /** 재사용 오픈의 워치독(ms). 페이지가 이미 있어 KikiInit ack 는 수십 ms 안에 온다. */
        private const val REOPEN_TIMEOUT_MS = 1500L
    }
}
