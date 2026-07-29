package com.langsense.app.util

import android.view.inputmethod.InputMethodSubtype

/**
 * IME 서브타입의 locale 파싱 + Samsung One UI 시스템 팝업 텍스트 → 언어 추론(fallback).
 *
 * 반환되는 언어 코드는 정규화된 2글자: "ko" / "en" / "ja" / "zh" / 기타.
 */
object ImeLocaleParser {

    const val KO = "ko"
    const val EN = "en"

    // [일본어 비활성화] 현재 일본어는 발음 입력 후 한자 변환 단계가 많아 한/영 감지의 실효가 낮아
    // 기능을 끈다. 삭제하지 않고 주석으로 보존하여 추후 재도입을 쉽게 한다.
    // 상수 자체는 재도입 시 참조를 되살리기 쉽도록 남겨 둔다(현재 미사용).
    const val JA = "ja"
    const val ZH = "zh"
    const val UNKNOWN = "unknown"

    /**
     * InputMethodSubtype → 정규화된 언어 코드.
     * minSdk 29 이므로 languageTag(API 24+)는 항상 사용 가능. 빈 값일 때만 deprecated 한
     * subtype.locale 로 폴백한다(그 폴백 참조 때문에 @Suppress("DEPRECATION") 유지).
     */
    @Suppress("DEPRECATION")
    fun parseLocale(subtype: InputMethodSubtype?): String {
        subtype ?: return UNKNOWN
        return normalize(subtype.languageTag.ifEmpty { subtype.locale })
    }

    /** 임의의 locale 문자열(ko_KR, en-US 등)을 2글자 코드로 정규화. */
    fun normalize(localeRaw: String?): String {
        val locale = localeRaw?.lowercase().orEmpty()
        if (locale.isEmpty()) return UNKNOWN
        return when {
            locale.startsWith("ko") -> KO
            // [일본어 비활성화] ja 전용 매핑 주석 처리(추후 재도입 위해 보존).
            // locale.startsWith("ja") -> JA
            locale.startsWith("en") -> EN
            locale.startsWith("zh") -> ZH
            else -> locale.take(2)
        }
    }

    /**
     * Samsung One UI IME 전환 시스템 팝업 텍스트에서 언어 추론 (fallback).
     * One UI 버전별 텍스트 패턴 차이를 흡수한다. 매칭 실패 시 null.
     *
     * | One UI | 한국어 패턴        | 영어 패턴            |
     * |--------|-------------------|---------------------|
     * | 3.x    | "한국어"          | "English"           |
     * | 5.x    | "한국어"          | "English (US)"      |
     * | 6.x    | "한국어"/"Korean" | "English"           |
     * | 7.x    | "한국어"          | "English"           |
     * | 8.x    | (실기기 확인 TBD) | (실기기 확인 TBD)    |
     */
    fun parseFromSystemPopupText(text: String?): String? {
        text ?: return null
        return when {
            text.contains("한국어") || text.contains("Korean", ignoreCase = true) -> KO
            text.contains("English", ignoreCase = true) -> EN
            // [일본어 비활성화] 日本語/Japanese 팝업 텍스트 감지 주석 처리(추후 재도입 위해 보존).
            // text.contains("日本語") || text.contains("Japanese", ignoreCase = true) -> JA
            text.contains("中文") || text.contains("Chinese", ignoreCase = true) -> ZH
            else -> null
        }
    }

    /** 화면 표시용 라벨 (플래시 중앙 텍스트). */
    fun displayName(lang: String): String = when (lang) {
        KO -> "한국어"
        EN -> "English"
        // [일본어 비활성화] JA -> "日本語" (추후 재도입 위해 보존)
        ZH -> "中文"
        else -> lang.uppercase()
    }

    /** 배지용 짧은 라벨. */
    fun badgeLabel(lang: String): String = when (lang) {
        KO -> "한"
        EN -> "EN"
        // [일본어 비활성화] JA -> "日" (추후 재도입 위해 보존)
        ZH -> "中"
        // 판별 실패는 사용자에게 "UN" 이라는 뜻 모를 라벨로 보이므로 중립 기호로 표시한다
        // (부팅 직후 등 아직 IME 서브타입을 못 읽은 상태에서 잠깐 나타날 수 있다).
        UNKNOWN -> "—"
        else -> lang.take(2).uppercase()
    }
}
