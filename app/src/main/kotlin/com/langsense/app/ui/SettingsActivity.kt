package com.langsense.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.langsense.app.R
import com.langsense.app.util.ImeLocaleParser
import com.langsense.app.util.Prefs
import kotlin.math.roundToInt

/**
 * 설정 화면 (프로그래매틱 UI, 성능 위주의 단순 구성).
 *
 * 모던 카드 레이아웃: 커스텀 톱바(뒤로가기) + 섹션별 라운드 카드([sectionCard]) + SwitchCompat 토글.
 * NoActionBar 테마(Theme.LangSense)를 쓰므로 supportActionBar 에 의존하지 않는다.
 *
 * - 지원 언어 스위치(기본 ON)
 * - 언어별 플래시 색상: #RRGGBB 직접 입력 + 32색 팔레트
 * - 깜박임 속도(100~500ms) / 횟수(1~5) 슬라이더
 * - 배지/포커스경고/한영타 토글 및 임계값
 *
 * 모든 변경은 즉시 SharedPreferences 에 반영되어 서비스에 적용된다(기능 표면은 리디자인 전과 1:1 동일).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var savedHint: TextView

    /**
     * 래디얼 메뉴 등 외부에서 바뀔 수 있는 토글(배지/플래시/한영타). 화면 재개 시 현재 값으로 다시
     * 동기화해 stale 표시를 막는다(이슈 4: 숨기기 후 설정 화면 토글이 안 꺼져 보이던 문제).
     */
    private val boundToggles = mutableListOf<Pair<CompoundButton, () -> Boolean>>()

    /** onResume 동기화 중 onCheckedChanged 가 prefs 를 되쓰지 않도록 막는 가드. */
    private var syncingToggles = false

    /**
     * 설정 화면이 떠 있는 동안에도 값이 밖에서 바뀔 수 있다 — 배지 오버레이는 이 화면 위에도
     * 떠 있어서, 보면서 배지를 탭해 래디얼 메뉴로 토글할 수 있기 때문. onResume 만으로는
     * (액티비티가 계속 포그라운드라 호출되지 않아) 스위치가 stale 로 남는다.
     */
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> syncToggles() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(28))
        }

        savedHint = TextView(this).apply {
            text = getString(R.string.settings_apply)
            setTextColor(getColor(R.color.ui_on_surface_muted))
            textSize = 12f
            setPadding(dp(2), 0, 0, dp(4))
        }
        content.addView(savedHint)

        // --- 지원 언어 ---
        content.addView(sectionCard(getString(R.string.settings_languages)).apply {
            addView(langSwitch(ImeLocaleParser.KO, R.string.settings_lang_ko))
            addView(langSwitch(ImeLocaleParser.EN, R.string.settings_lang_en))
            // [일본어 비활성화] 일본어 토글 주석(추후 재도입 위해 보존).
            // addView(langSwitch(ImeLocaleParser.JA, R.string.settings_lang_ja))
        })

        // --- 터치 키보드 제외 (추가 기능 2) ---
        content.addView(sectionCard(getString(R.string.settings_exclude_touch_kb)).apply {
            addView(descRow(getString(R.string.settings_exclude_touch_kb_desc)))
            addView(switchRow(getString(R.string.settings_exclude_touch_kb_enabled), prefs.excludeTouchKeyboard) {
                prefs.excludeTouchKeyboard = it; markSaved()
            })
        })

        // --- 전환 플래시 ---
        content.addView(sectionCard(getString(R.string.settings_flash)).apply {
            addView(boundSwitchRow(getString(R.string.settings_flash_enabled), { prefs.flashEnabled }) {
                prefs.flashEnabled = it; markSaved()
            })
            // 깜박임 속도 100~500ms
            addView(
                sliderRow(
                    label = getString(R.string.settings_flash_duration),
                    min = 100, max = 500, step = 50, value = prefs.flashDurationMs, suffix = "ms"
                ) { prefs.flashDurationMs = it; markSaved() }
            )
            // 깜박임 횟수 1~5
            addView(
                sliderRow(
                    label = getString(R.string.settings_flash_count),
                    min = 1, max = 5, step = 1, value = prefs.flashCount, suffix = "회"
                ) { prefs.flashCount = it; markSaved() }
            )
        })

        // --- 언어별 플래시 색상 ---
        content.addView(sectionCard(getString(R.string.settings_flash_colors)).apply {
            addView(colorEditor(ImeLocaleParser.KO, getString(R.string.settings_lang_ko)))
            addView(colorEditor(ImeLocaleParser.EN, getString(R.string.settings_lang_en)))
            // [일본어 비활성화] 일본어 색상 편집기 주석(추후 재도입 위해 보존).
            // addView(colorEditor(ImeLocaleParser.JA, getString(R.string.settings_lang_ja)))
        })

        // --- 상시 배지 ---
        content.addView(sectionCard(getString(R.string.settings_badge)).apply {
            addView(boundSwitchRow(getString(R.string.settings_badge_enabled), { prefs.badgeEnabled }) {
                prefs.badgeEnabled = it; markSaved()
            })
            // [5] 크기 3단계(소/중/대)
            addView(badgeSizeRow())
            // [5] 배경색/글씨색 — 플래시 색상과 동일한 공용 색 선택 컴포넌트 재사용
            addView(colorPickerRow(getString(R.string.settings_badge_bg_color), prefs.badgeBgColorHex) {
                prefs.badgeBgColorHex = it
            })
            addView(colorPickerRow(getString(R.string.settings_badge_text_color), prefs.badgeTextColorHex) {
                prefs.badgeTextColorHex = it
            })
        })

        // --- 플로팅 메뉴(배지 탭) ---
        content.addView(sectionCard(getString(R.string.settings_radial)).apply {
            addView(descRow(getString(R.string.settings_radial_reduce_motion_desc)))
            addView(switchRow(getString(R.string.settings_radial_reduce_motion), prefs.radialReduceMotion) {
                prefs.radialReduceMotion = it; markSaved()
            })
        })

        // --- 포커스 없는 키 입력 경고 ---
        content.addView(sectionCard(getString(R.string.settings_nofocus)).apply {
            addView(descRow(getString(R.string.settings_nofocus_desc))) // [3] 기능 설명
            addView(switchRow(getString(R.string.settings_nofocus_enabled), prefs.noFocusEnabled) {
                prefs.noFocusEnabled = it; markSaved()
            })
            addView(
                sliderRow(
                    label = getString(R.string.settings_nofocus_threshold),
                    min = 1, max = 5, step = 1, value = prefs.noFocusThreshold, suffix = "회"
                ) { prefs.noFocusThreshold = it; markSaved() }
            )
        })

        // --- 한영타 교체 ---
        content.addView(sectionCard(getString(R.string.settings_replace)).apply {
            addView(boundSwitchRow(getString(R.string.settings_replace_enabled), { prefs.replaceEnabled }) {
                prefs.replaceEnabled = it; markSaved()
            })
            addView(
                sliderRow(
                    label = getString(R.string.settings_replace_confidence),
                    min = 50, max = 90, step = 5, value = prefs.replaceConfidence, suffix = "%"
                ) { prefs.replaceConfidence = it; markSaved() }
            )
            addView(descRow(getString(R.string.settings_replace_confidence_desc))) // [4] 신뢰도 설명
        })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            ).also { it.weight = 1f }
        }

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar())
            addView(scroll)
        }
        setContentView(screen)
    }

    /** 커스텀 톱바: 뒤로가기 + 화면 제목 (NoActionBar 테마 대응). */
    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(20), dp(6))
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = getString(R.string.settings_back)
            background = rippleCircleBackground()
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 20f
            setTextColor(getColor(R.color.ui_on_surface))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.weight = 1f; it.marginStart = dp(8) }
        }
        bar.addView(back)
        bar.addView(title)
        return bar
    }

    /** 테마의 원형(borderless) 리플 배경 — 톱바 뒤로가기 버튼용. */
    private fun rippleCircleBackground(): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return ContextCompat.getDrawable(this, tv.resourceId)
    }

    override fun onResume() {
        super.onResume()
        // 외부(래디얼 메뉴)에서 바뀐 토글 값을 현재 prefs 기준으로 다시 맞춘다(stale 표시 방지).
        syncToggles()
        prefs.register(prefsListener)
    }

    override fun onPause() {
        prefs.unregister(prefsListener)
        super.onPause()
    }

    private fun syncToggles() {
        syncingToggles = true
        boundToggles.forEach { (toggle, get) -> toggle.isChecked = get() }
        syncingToggles = false
    }

    /**
     * 외부에서도 바뀔 수 있는 토글 행. 일반 [switchRow] 와 같지만 [boundToggles] 에 등록해
     * onResume 에서 현재 값으로 재동기화한다. 동기화 중에는 [syncingToggles] 가드로 되쓰기를 막는다.
     */
    private fun boundSwitchRow(label: String, get: () -> Boolean, set: (Boolean) -> Unit): SwitchCompat =
        switchRow(label, get()) { if (!syncingToggles) set(it) }
            .also { boundToggles.add(it to get) }

    private fun markSaved() {
        savedHint.text = getString(R.string.settings_saved)
    }

    // ---------------------------------------------------------------------
    // UI 빌더
    // ---------------------------------------------------------------------

    /** 섹션 카드: 라운드 surface 컨테이너 + 섹션 제목. 항목들은 이 안에 addView 한다. */
    private fun sectionCard(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_card)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(12) }
        addView(TextView(this@SettingsActivity).apply {
            text = title
            textSize = 15f
            setTextColor(getColor(R.color.ui_accent))
            letterSpacing = 0.01f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
    }

    /** 항목 아래 붙는 짧은 회색 설명 문구(기능/수치 해설). */
    private fun descRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(getColor(R.color.ui_on_surface_muted))
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun langSwitch(lang: String, labelRes: Int): SwitchCompat =
        switchRow(getString(labelRes), prefs.isLangEnabled(lang)) { checked ->
            prefs.setLangEnabled(lang, checked); markSaved()
        }

    /** 토글 행: 라벨 좌측 + 스위치 우측 (SwitchCompat 자체 텍스트 배치 활용). */
    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(this).apply {
            text = label
            textSize = 15f
            setTextColor(getColor(R.color.ui_on_surface))
            isChecked = initial
            minHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    /**
     * 정수 슬라이더 행. SeekBar 는 0..((max-min)/step) 범위로 매핑. 라벨 좌측 + 현재 값 우측 정렬.
     */
    private fun sliderRow(
        label: String,
        min: Int,
        max: Int,
        step: Int,
        value: Int,
        suffix: String,
        onChange: (Int) -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.ui_on_surface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.weight = 1f }
        })
        val valueLabel = TextView(this).apply {
            text = getString(R.string.slider_value_short_format, value, suffix)
            textSize = 14f
            setTextColor(getColor(R.color.ui_accent))
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(valueLabel)

        val steps = (max - min) / step
        val seek = SeekBar(this).apply {
            this.max = steps
            progress = ((value - min) / step).coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    valueLabel.text = getString(R.string.slider_value_short_format, v, suffix)
                    // 저장을 onStopTrackingTouch 에만 두면 터치 드래그로만 값이 바뀐다 —
                    // 키보드/D-pad/TalkBack 조작은 이 콜백만 오므로 화면 숫자만 바뀌고 저장이
                    // 안 됐다(접근성 앱의 설정 화면이 접근성 조작 불가였던 문제).
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    onChange(min + (sb?.progress ?: 0) * step)
                }
            })
        }
        container.addView(header)
        container.addView(seek)
        return container
    }

    /** 언어별 플래시 색상 편집기 — 공용 [colorPickerRow] 를 prefs.colorHex 에 연결. */
    private fun colorEditor(lang: String, langLabel: String): View =
        colorPickerRow(langLabel, prefs.colorHex(lang)) { hex -> prefs.setColorHex(lang, hex) }

    /**
     * 공용 색 선택 컴포넌트: 라벨 + 원형 미리보기 + #RRGGBB 입력 + 32색 원형 팔레트.
     * 플래시 색/배지 색 설정이 동일 코드를 공유한다(복붙 방지). 선택/입력 시 [onPicked] 에
     * 정규화된 "#RRGGBB" 를 넘기고 저장 안내를 표시한다.
     */
    private fun colorPickerRow(
        label: String,
        initialHex: String,
        onPicked: (String) -> Unit
    ): View {
        // 마지막으로 확정된 유효 색. 잘못된 입력으로 commit 이 거부될 때 입력칸을 되돌리는 기준.
        var currentHex = initialHex
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
        val hexInput = EditText(this).apply {
            setText(initialHex)
            filters = arrayOf(InputFilter.LengthFilter(7))
            setSingleLine()
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_color_hex)
            layoutParams = LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.marginStart = dp(14) }
        }
        applyPreview(preview, initialHex)

        fun commit(raw: String) {
            // 파싱 실패 시 normalizeHex 는 기본 회색을 돌려준다 — 그대로 저장하면 입력 도중
            // 포커스를 잃었을 때(팔레트 스크롤 등) 사용자의 기존 색이 말없이 회색으로 날아간다.
            // 유효하지 않으면 저장하지 않고 현재 값으로 입력칸만 되돌린다.
            val normalized = Prefs.normalizeHexOrNull(raw)
            if (normalized == null) {
                hexInput.setText(currentHex)
                return
            }
            currentHex = normalized
            hexInput.setText(normalized)
            applyPreview(preview, normalized)
            onPicked(normalized)
            markSaved()
        }
        hexInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { commit(hexInput.text.toString()); true } else false
        }
        hexInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit(hexInput.text.toString()) }

        headerRow.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.ui_on_surface))
            layoutParams = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        headerRow.addView(preview)
        headerRow.addView(hexInput)
        container.addView(headerRow)

        // 32색 팔레트 (8 x 4, 원형 스와치)
        val palette = GridLayout(this).apply {
            columnCount = 8
            setPadding(0, dp(8), 0, 0)
        }
        PALETTE.forEach { hex ->
            val swatch = View(this).apply {
                val lp = GridLayout.LayoutParams().apply {
                    width = dp(32); height = dp(32)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                layoutParams = lp
                background = swatchDrawable(hex)
                setOnClickListener { commit(hex) }
            }
            palette.addView(swatch)
        }
        container.addView(palette)
        return container
    }

    /** 배지 크기 3단계(소/중/대) 라디오 선택. */
    private fun badgeSizeRow(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.settings_badge_size)
            textSize = 14f
            setTextColor(getColor(R.color.ui_on_surface))
        })
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val labels = listOf(
            R.string.settings_badge_size_small,
            R.string.settings_badge_size_medium,
            R.string.settings_badge_size_large
        )
        val buttons = labels.map { res ->
            RadioButton(this).apply {
                id = View.generateViewId()
                setText(res)
                textSize = 14f
                setPadding(0, 0, dp(16), 0)
            }.also { group.addView(it) }
        }
        buttons.getOrNull(prefs.badgeSize)?.isChecked = true
        group.setOnCheckedChangeListener { _, checkedId ->
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx >= 0) { prefs.badgeSize = idx; markSaved() }
        }
        container.addView(group)
        return container
    }

    private fun applyPreview(view: View, hex: String) {
        view.background = swatchDrawable(hex)
    }

    private fun swatchDrawable(hex: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(runCatching { Color.parseColor(hex) }.getOrDefault(Color.GRAY))
        setStroke(dp(1), getColor(R.color.ui_divider))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).roundToInt()

    companion object {
        /** 32색 팔레트. */
        private val PALETTE = listOf(
            "#000000", "#444444", "#888888", "#CCCCCC", "#FFFFFF",
            "#CC2D2D", "#E53935", "#FF6F61", "#FF1744",
            "#1A6EBD", "#1565C0", "#2196F3", "#00B0FF",
            "#2D8C4E", "#2E7D32", "#43A047", "#00C853",
            "#F9A825", "#FBC02D", "#FF9800", "#FF6D00",
            "#6A1B9A", "#8E24AA", "#AB47BC", "#D500F9",
            "#00838F", "#0097A7", "#26C6DA",
            "#5D4037", "#795548",
            "#AD1457", "#EC407A"
        )
    }
}
