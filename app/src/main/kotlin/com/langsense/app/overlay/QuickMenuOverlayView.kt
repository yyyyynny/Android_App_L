package com.langsense.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray

/** 간편 메뉴 항목(표시 라벨 + 탭 동작). */
data class QuickMenuItem(val label: String, val onClick: () -> Unit)

/**
 * 플로팅 래디얼 메뉴 (추가 기능 1).
 *
 * **사용자가 제공한 원본 HTML(`design/reference/radialmenu.html` = `assets/radialmenu.html`)을
 * WebView 로 그대로 렌더링한다.** 과거의 네이티브(Canvas) 재해석은 원본과 미세하게 달라 폐기했고,
 * 이제 외형·모션은 그 HTML 파일이 100% 진실이다. 앱에서 바꾼 것은 단 두 가지(사용자 요청):
 *  - 선 위를 이동하던 빛 점(travel dot) 제거
 *  - 대신 선이 약하게 움직이도록(#lineSway) 변경
 * 그 외 앱 통합용 배선(자체 배지 숨김·자동 오픈·JS↔네이티브 브리지)은 보이는 디자인을 바꾸지 않는다.
 *
 * 통합 방식:
 *  - 페이지 로드 후 [WebBridge] 를 통해 `KikiInit({anchorX,anchorY(dp), reduceMotion, labels})` 호출 →
 *    네이티브 배지 위치에 팬을 맞추고, 라벨을 주입하고, 저사양 모드를 반영한 뒤 자동으로 펼친다.
 *  - 오브 탭 → JS 가 `KikiNative.onItemTap(i)` → 해당 [QuickMenuItem] 실행 후 수납.
 *  - 스크림(빈 곳) 탭 → JS 가 `KikiNative.onDismiss()` → 수납.
 *  - 배지 재탭( [requestCollapse] ) → JS `KikiCollapse()` 로 수납 애니메이션 후 창 제거.
 *
 * 생성자 시그니처와 [requestCollapse] 는 기존 [OverlayManager] 배선을 그대로 쓰도록 유지한다.
 * WebView 는 프레임워크(android.webkit)라 외부 의존성 추가가 없다(최소 의존성 원칙 준수).
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class QuickMenuOverlayView(
    context: Context,
    /** 앵커(배지 중심) 좌표(px)를 그때그때 조회. 회전 등으로 배지가 옮겨져도 최신값을 쓴다(Bug 6). */
    private val anchorProvider: () -> Pair<Int, Int>,
    private val items: List<QuickMenuItem>,
    /** 저사양(움직임 줄이기) 모드: 원본 HTML 의 연속 애니메이션(오브 morph/부유/별/먼지/선 sway)을 끈다. */
    private val reduceMotion: Boolean,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissed = false

    /** HTML 로드가 완료돼 메뉴가 초기화됐는지(워치독이 창을 닫을지 판단). */
    private var menuReady = false

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
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame != false) mainHandler.post { dismiss() }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                // true 를 반환해야 앱이 함께 죽지 않는다(반환 안 하면 프로세스 종료).
                mainHandler.post { dismiss() }
                return true
            }
        }
    }

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.loadUrl("file:///android_asset/radialmenu.html")
        // 워치독: 로드가 끝내 완료되지 않으면(에러 콜백조차 안 오는 경우 포함) 창을 닫는다.
        mainHandler.postDelayed({ if (!menuReady) dismiss() }, LOAD_TIMEOUT_MS)
    }

    /** 로드 완료 후 배지 위치(dp)·라벨·저사양 여부를 HTML 로 주입하고 자동 펼침. */
    private fun initMenu() {
        if (dismissed || !isAttachedToWindow) return
        menuReady = true
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        val (ax, ay) = anchorProvider()
        val anchorXdp = ax / density
        val anchorYdp = ay / density
        val labelsJson = JSONArray(items.map { it.label }).toString()
        val cfg = "{anchorX:${anchorXdp}, anchorY:${anchorYdp}, " +
            "reduceMotion:${reduceMotion}, labels:${labelsJson}}"
        webView.evaluateJavascript("window.KikiInit && window.KikiInit($cfg);", null)
    }

    /** 외부(배지 재탭/스크림)에서 우아한 수납 요청 — 수납 애니메이션 후 창 제거. */
    fun requestCollapse() {
        if (dismissed) return
        webView.evaluateJavascript("window.KikiCollapse && window.KikiCollapse();", null)
        mainHandler.postDelayed({ dismiss() }, COLLAPSE_REMOVE_MS)
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true
        onDismiss()
    }

    override fun onDetachedFromWindow() {
        // 외부 경로(배지 숨김 → hideQuickMenuInternal 등)로 창이 제거된 경우에도 이후의 늦은
        // 콜백이 destroy 된 WebView 를 만지지 못하게 표시한다(onItemTap 가드 참조).
        dismissed = true
        // 창에서 제거되면 예약된 콜백 정리 + WebView 자원 해제(누수 방지).
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDetachedFromWindow()
    }

    /** JS→네이티브 브리지. JS 스레드에서 호출되므로 메인 스레드로 넘겨 처리한다. */
    private inner class WebBridge {
        @JavascriptInterface
        fun onItemTap(index: Int) {
            mainHandler.post {
                items.getOrNull(index)?.onClick?.invoke()
                // "배지 숨기기" 항목은 onClick 안에서 이 메뉴 창 제거(→ WebView destroy)까지
                // 동기로 이어질 수 있다. 그 뒤 destroy 된 WebView 에 evaluateJavascript 를
                // 호출하지 않도록 가드한다.
                if (dismissed || !isAttachedToWindow) return@post
                // 수납 애니메이션을 잠깐 보인 뒤 창 제거(항목 실행은 이미 완료).
                webView.evaluateJavascript("window.KikiCollapse && window.KikiCollapse();", null)
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
         * 로드 워치독(ms). 이 시간 안에 메뉴가 초기화되지 않으면 창을 제거한다 —
         * 전체 화면을 덮고 터치를 소비하는 창이라 열리지도 닫히지도 않으면 화면이 잠긴다.
         * 저사양 기기의 콜드 WebView 초기화(수백 ms~1초대)를 충분히 덮는 값.
         */
        private const val LOAD_TIMEOUT_MS = 3000L
    }
}
