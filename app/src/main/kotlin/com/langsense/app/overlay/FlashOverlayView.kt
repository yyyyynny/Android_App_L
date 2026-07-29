package com.langsense.app.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 전체화면 플래시 오버레이 (Feature 1, 3).
 *
 * 색상 배경 + 중앙 언어명 텍스트를 [count] 회 깜박인다.
 * 각 깜박임은 "즉시 표시 → [durationMs] 동안 페이드아웃" 으로 주변시 인지에 최적화.
 */
class FlashOverlayView(context: Context) : FrameLayout(context) {

    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 28f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /** 다음 깜박임 사이클 예약(보류) — 취소 시 정확히 제거하기 위해 참조를 보관한다. */
    private var pendingCycle: Runnable? = null

    /**
     * cancel() 이 진행 중임을 표시. ViewPropertyAnimator.cancel() 은 진행 중이던 withEndAction 을
     * "정상 종료"와 동일하게 동기 호출하는 특성이 있어(취소인데도 실행됨), 이 플래그 없이는
     * removeFlash() → cancel() → withEndAction → onEnd() → removeFlash() 가 같은 콜스택에서
     * 재귀 호출된다(QuickMenuOverlayView.runAnimator 의 cancelled 플래그와 동일한 이유로 도입).
     */
    private var cancelled = false

    init {
        addView(
            label,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        // 그림자로 어떤 배경색에서도 텍스트 가독성 확보
        label.setShadowLayer(8f, 0f, 0f, Color.argb(180, 0, 0, 0))
    }

    /**
     * 플래시 재생.
     * @param colorArgb 불투명도가 이미 적용된 ARGB 색상
     * @param text 중앙 표시 텍스트
     * @param durationMs 1회 페이드아웃 시간(=깜박임 속도)
     * @param count 깜박임 횟수
     * @param onEnd 모든 깜박임 종료 후 콜백(오버레이 제거용)
     */
    fun play(colorArgb: Int, text: String, durationMs: Int, count: Int, onEnd: () -> Unit) {
        cancelled = false
        setBackgroundColor(colorArgb)
        label.text = text
        playCycle(count.coerceAtLeast(1), durationMs.toLong(), onEnd)
    }

    /** 진행 중인 애니메이션/예약 콜백을 모두 취소한다(다른 플래시로 교체될 때 누수·오제거 방지). */
    fun cancel() {
        cancelled = true
        animate().cancel()
        cancelPendingCycle()
    }

    override fun onDetachedFromWindow() {
        // 윈도우에서 제거되면 더 이상 콜백이 의미 없으므로 정리한다.
        cancelled = true
        animate().cancel()
        cancelPendingCycle()
        super.onDetachedFromWindow()
    }

    /**
     * 보류 중인 다음 사이클 예약을 정확히 제거한다.
     * (이전 구현의 removeCallbacks(null) 은 게시된 람다 콜백이 null 이 아니라 실제로는 아무것도
     * 제거하지 못했다 — 뷰 제거 후에도 예약이 살아남는 누수/레이스의 원인이었다. Bug 4 감사)
     */
    private fun cancelPendingCycle() {
        pendingCycle?.let { removeCallbacks(it) }
        pendingCycle = null
    }

    private fun playCycle(remaining: Int, durationMs: Long, onEnd: () -> Unit) {
        animate().cancel()
        alpha = 1f
        animate()
            .alpha(0f)
            .setStartDelay(durationMs / 2) // 잠깐 보이게 유지 후 페이드아웃
            .setDuration(durationMs)
            .withEndAction {
                // cancel() 에 의한 호출(재진입)이면 무시한다 — 그렇지 않으면 cancel() 호출 도중
                // 이 콜백이 다시 removeFlash() 를 유발해 재귀 호출된다.
                // 과거엔 `|| !isAttachedToWindow` 도 함께 걸었는데, 그 경로로 빠지면 onEnd() 가
                // 영영 호출되지 않아 투명해진 전체화면 창이 무기한 잔류했다(창 누수). 윈도우에서
                // 떨어지는 경로(onDetachedFromWindow)는 항상 cancelled 를 먼저 세우므로 이 조건만으로
                // 재진입 보호는 충분하다.
                if (cancelled) return@withEndAction
                if (remaining > 1) {
                    val next = Runnable {
                        pendingCycle = null
                        playCycle(remaining - 1, durationMs, onEnd)
                    }
                    pendingCycle = next
                    postDelayed(next, INTER_BLINK_MS)
                } else {
                    onEnd()
                }
            }
            .start()
    }

    companion object {
        /** 깜박임 사이클 사이의 짧은 간격(ms). */
        private const val INTER_BLINK_MS = 60L

        /**
         * [play] 가 실제로 화면을 점유하는 총 시간(ms).
         *
         * 1사이클 = 유지([playCycle] 의 startDelay = dur/2) + 페이드아웃(dur) = 1.5×dur 이고,
         * 사이클 사이에 [INTER_BLINK_MS] 가 끼므로 총 길이는 설정값(dur)보다 항상 길다.
         * OverlayManager 의 동일 색 중복 억제 창이 이 실제 재생 길이에 연동되도록 여기(사이클
         * 구조가 정의된 파일)에 진실 수식을 둔다.
         */
        fun totalDurationMs(durationMs: Int, count: Int): Long {
            val n = count.coerceAtLeast(1)
            val dur = durationMs.toLong()
            return n * (dur + dur / 2) + (n - 1) * INTER_BLINK_MS
        }
    }
}
