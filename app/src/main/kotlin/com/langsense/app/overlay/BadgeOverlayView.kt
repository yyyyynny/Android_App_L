package com.langsense.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 상시 언어 배지 (Feature 2). 화면 모서리에 현재 입력 언어를 항상 표시.
 * 드래그로 위치 변경 가능하며, 드래그 종료 시 위치를 콜백으로 저장한다.
 */
@SuppressLint("ViewConstructor")
class BadgeOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val onTap: () -> Unit,
    private val onPositionSaved: (x: Int, y: Int) -> Unit
) : AppCompatTextView(context) {

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    init {
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        // 크기·색은 applyStyle 로 적용한다(설정 변경 시 재호출로 즉시 반영). 기본값은 중(1)+기존 색.
        applyStyle(SIZE_MEDIUM, DEFAULT_BG_ARGB, DEFAULT_TEXT_ARGB)
        setupTouch()
    }

    fun setLanguage(label: String) {
        text = label
    }

    /** 탭 동작을 performClick 경유로 둬 TalkBack 등 접근성 도구로도 메뉴를 열 수 있게 한다. */
    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    /**
     * 배지 탭 피드백 펄스 (추가 기능 1, radial-menu.html 의 badge pulse 이식).
     * 래디얼 메뉴를 열거나 닫을 때 배지가 살짝 커졌다 돌아오는 짧은 스케일 연출. 외형/설정은 바꾸지 않는다.
     */
    fun pulse() {
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        animate()
            .scaleX(PULSE_SCALE).scaleY(PULSE_SCALE)
            .setDuration(PULSE_HALF_MS)
            .withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(PULSE_HALF_MS).start()
            }
            .start()
    }

    /**
     * 크기 단계(0=소, 1=중, 2=대)와 배경/글씨 ARGB 를 적용한다(Feature 2 커스터마이즈).
     *
     * 발광 스타일(orb_mockup.html 의 `.badge` 이식): 본체(사용자 배경색, 코너 8dp) 둘레에
     * 글씨색에서 파생한 **halo 2겹**(box-shadow 글로우 근사)과 **안쪽 1dp 링**(inset ring),
     * 글자에는 글씨색 글로우([setShadowLayer])를 준다. 색을 바꾸면 발광도 그 색을 따라가므로
     * 사용자 커스터마이즈(배경/글씨색·크기 3단계)는 그대로 보존된다.
     */
    fun applyStyle(sizeLevel: Int, bgArgb: Int, textArgb: Int) {
        val spec = sizeSpec(sizeLevel)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.textSp)
        setTextColor(textArgb)
        // 글자 발광 — 목업 배지 글자의 글로우. 글씨색 기반이라 어떤 커스텀 색에도 어울린다.
        setShadowLayer(dp(4f).toFloat(), 0f, 0f, withAlpha(textArgb, GLOW_TEXT_ALPHA))

        // halo 두께만큼 패딩/최소폭을 늘려 본체 크기(시각적 배지 크기)는 기존과 동일하게 유지.
        val halo = dp(HALO_THICKNESS_DP)
        val padH = dp(spec.padHDp) + halo
        val padV = dp(spec.padVDp) + halo
        setPadding(padH, padV, padH, padV)
        minWidth = dp(spec.minWDp) + halo * 2

        // halo 는 채움이 아니라 **테두리 링** 2겹 — 사용자 배경이 반투명(기본 80% 검정)이어도
        // 본체 뒤로 halo 가 비쳐 배경색이 탁해지지 않는다(글로우는 본체 바깥에만 번짐).
        val mid = (halo / 2).coerceAtLeast(1)
        val outerHalo = ringRect(withAlpha(textArgb, HALO_OUTER_ALPHA), 12f, mid)
        val innerHalo = ringRect(withAlpha(textArgb, HALO_INNER_ALPHA), 10f, mid)
        val body = roundRect(bgArgb, 8f).apply {
            setStroke(dp(1f), withAlpha(textArgb, RING_ALPHA))
        }
        background = LayerDrawable(arrayOf(outerHalo, innerHalo, body)).apply {
            setLayerInset(1, mid, mid, mid, mid)
            setLayerInset(2, halo, halo, halo, halo)
        }
    }

    private fun roundRect(argb: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(argb)
    }

    /** 투명 채움 + 지정 굵기 테두리만 있는 라운드 사각(halo 링용). */
    private fun ringRect(argb: Int, radiusDp: Float, strokeWidthPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(strokeWidthPx, argb)
        }

    /** [argb] 의 RGB 는 유지하고 알파만 [alpha](0~1)로 바꾼다. */
    private fun withAlpha(argb: Int, alpha: Float): Int =
        (argb and 0x00FFFFFF) or (((alpha * 255).toInt().coerceIn(0, 255)) shl 24)

    private data class SizeSpec(val textSp: Float, val padHDp: Float, val padVDp: Float, val minWDp: Float)

    private fun sizeSpec(level: Int): SizeSpec = when (level) {
        SIZE_SMALL -> SizeSpec(12f, 6f, 3f, 32f)
        SIZE_LARGE -> SizeSpec(18f, 11f, 6f, 52f)
        else -> SizeSpec(14f, 8f, 4f, 40f) // 중(기본) = 기존 외형
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragging = true
                    params.x = clampX(startX + dx)
                    params.y = clampY(startY + dy)
                    runCatching { windowManager.updateViewLayout(this, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 드래그면 위치 저장, 단순 탭이면 간편 메뉴 열기.
                    if (dragging) onPositionSaved(params.x, params.y) else performClick()
                    true
                }
                // 드래그 중 시스템이 제스처를 가로채면 CANCEL 로 끝난다 — 여기서 저장하지 않으면
                // 옮긴 위치가 유실돼 다음 표시 때 예전 자리로 돌아간다.
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onPositionSaved(params.x, params.y)
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 현재 params 좌표가 화면 밖이면 화면 안으로 되돌린다(회전/해상도 변경, 저장 좌표 복원 대비).
     * 저장값(prefs)은 건드리지 않는다 — 사용자가 저장한 위치는 그대로 두고, 표시할 때마다 그
     * 시점의 화면에 맞춰 보정만 한다([moveWithinScreen] 참조).
     */
    fun ensureOnScreen() = moveWithinScreen(params.x, params.y)

    /**
     * [x], [y] 로 옮기되 화면 경계 안으로 보정한다.
     *
     * 회전 시 저장값을 **다시** 적용하는 데 쓴다 — 클램프는 파괴적이라(세로에서 아래쪽이던 배지가
     * 가로에서 위로 눌린 뒤 다시 세로로 돌아와도 눌린 좌표가 유효 범위라 복원되지 않는다),
     * 매번 저장 원본에서 출발해야 "회전 왕복 시 원위치 복귀"가 성립한다.
     */
    fun moveWithinScreen(x: Int, y: Int) {
        val cx = clampX(x)
        val cy = clampY(y)
        if (cx == params.x && cy == params.y) return
        params.x = cx
        params.y = cy
        if (isAttachedToWindow) runCatching { windowManager.updateViewLayout(this, params) }
    }

    private val touchSlop: Int
        get() = dp(6f)

    private fun clampX(x: Int): Int {
        val max = (screenWidth() - width).coerceAtLeast(0)
        return x.coerceIn(0, max)
    }

    private fun clampY(y: Int): Int {
        val max = (screenHeight() - height).coerceAtLeast(0)
        return y.coerceIn(0, max)
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    ).roundToInt()

    companion object {
        const val SIZE_SMALL = 0
        const val SIZE_MEDIUM = 1
        const val SIZE_LARGE = 2

        /** 탭 펄스: 최대 배율과 반(half) 지속 시간(ms). */
        private const val PULSE_SCALE = 1.12f
        private const val PULSE_HALF_MS = 130L

        /** 발광 halo 두께(dp)와 각 레이어 알파(글씨색 기반 — 목업 box-shadow/inset ring 근사). */
        private const val HALO_THICKNESS_DP = 4f
        private const val HALO_OUTER_ALPHA = 0.15f
        private const val HALO_INNER_ALPHA = 0.28f
        private const val RING_ALPHA = 0.40f
        private const val GLOW_TEXT_ALPHA = 0.55f

        /** init 기본값(=기존 외형). OverlayManager 가 곧바로 prefs 값으로 applyStyle 재호출한다. */
        private const val DEFAULT_BG_ARGB = 0xCC000000.toInt()
        private const val DEFAULT_TEXT_ARGB = 0xFFFFFFFF.toInt()

        /** 우하단 기본 위치 계산 (gravity TOP|START 기준 좌표). */
        fun defaultPosition(view: View): Pair<Int, Int> {
            val dm = view.resources.displayMetrics
            val margin = (16 * dm.density).roundToInt()
            val w = (56 * dm.density).roundToInt()
            val h = (28 * dm.density).roundToInt()
            return (dm.widthPixels - w - margin) to (dm.heightPixels - h - margin * 4)
        }
    }
}
