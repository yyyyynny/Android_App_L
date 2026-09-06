package com.langsense.app.service

import android.view.accessibility.AccessibilityNodeInfo
import com.langsense.app.util.HangulConverter

/**
 * 드래그 선택 + 한영타 판정 (Feature 4).
 *
 * 서비스가 TYPE_VIEW_TEXT_SELECTION_CHANGED 이벤트의 소스 노드를 **한 번만** 얻어 넘긴다
 * (이전엔 입력 실착 확인과 여기서 각각 `event.source` 를 호출해 이벤트당 노드 IPC 가 2회였다).
 * 선택 구간이 한영타면 [onDetected] 로 노드 소유권을 넘기고 true 를 반환한다 — 그 외에는 노드를
 * 건드리지 않으며(recycle 은 호출자 책임) false.
 *
 * 비용 원칙: 선택 변경은 드래그 중 초당 수십 회 오고 전부 메인 스레드다. 전체 텍스트를 복사하지
 * 않고(`CharSequence` 로 받아 선택 구간만 `subSequence`), 변환은 [HangulConverter.analyze] 로 1회만,
 * 전체 텍스트 `toString()` 은 실제로 칩을 띄우는 순간에만 한다.
 */
class TextSelectionMonitor(
    private val confidencePercentProvider: () -> Int,
    private val onDetected: (
        node: AccessibilityNodeInfo,
        fullText: String,
        selStart: Int,
        selEnd: Int,
        converted: String
    ) -> Unit
) {
    /** @return true 면 [onDetected] 로 [node] 소유권을 넘겼다(호출자는 recycle 하지 않는다). */
    fun onSelectionChanged(node: AccessibilityNodeInfo): Boolean {
        val text: CharSequence = runCatching { node.text }.getOrNull() ?: return false
        if (text.isEmpty()) return false

        var selStart = runCatching { node.textSelectionStart }.getOrDefault(-1)
        var selEnd = runCatching { node.textSelectionEnd }.getOrDefault(-1)
        if (selStart > selEnd) {
            val t = selStart; selStart = selEnd; selEnd = t
        }
        if (selStart < 0 || selEnd <= selStart || selEnd > text.length) return false

        // 전체 선택(Ctrl+A) 같은 대량 선택은 한영타 교정 대상이 아니다. 상한이 없으면 메인 스레드에서
        // 수만 자를 변환하고, 교체 시 전체 텍스트를 Binder 로 넘겨 트랜잭션 한도(≈1MB)를 넘길 수 있다.
        val len = selEnd - selStart
        if (len < MIN_SELECTION || len > MAX_SELECTION) return false

        val selected = text.subSequence(selStart, selEnd).toString()
        if (selected.isBlank()) return false

        val analysis = HangulConverter.analyze(selected)
        if (analysis.confidence * 100f < confidencePercentProvider()) return false
        if (analysis.converted == selected) return false // 변환 결과가 동일하면 의미 없음

        onDetected(node, text.toString(), selStart, selEnd, analysis.converted)
        return true
    }

    companion object {
        const val MIN_SELECTION = 2

        /** 한영타 교정이 의미 있는 선택 길이 상한(문자). 단어~짧은 문장 범위를 넉넉히 덮는다. */
        const val MAX_SELECTION = 200
    }
}
