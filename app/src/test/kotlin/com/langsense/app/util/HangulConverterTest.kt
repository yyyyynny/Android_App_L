package com.langsense.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HangulConverter 두벌식 오토마타 검증.
 * 순수 JVM 테스트(안드로이드 의존성 없음) — `./gradlew testDebugUnitTest` 로 실행.
 */
class HangulConverterTest {

    @Test
    fun engToKor_basic() {
        assertEquals("나무위키", HangulConverter.convertEngToKor("skandnlzl"))
        assertEquals("안드로이드", HangulConverter.convertEngToKor("dksemfhdlem"))
        assertEquals("안녕", HangulConverter.convertEngToKor("dkssud"))
        assertEquals("한글", HangulConverter.convertEngToKor("gksrmf"))
        assertEquals("이렇게", HangulConverter.convertEngToKor("dlfjgrp"))
    }

    @Test
    fun engToKor_preservesNonLetters() {
        assertEquals("강 장치", HangulConverter.convertEngToKor("rkd wkdcl"))
        assertEquals("ㅇ마123", HangulConverter.convertEngToKor("dak123"))
    }

    @Test
    fun engToKor_linking_dokkaebibul() {
        // 종성 뒤 모음 → 연음(도깨비불)
        assertEquals("앉아", HangulConverter.convertEngToKor("dkswdk")) // ㄵ → ㄴ + ㅈ 이동
        assertEquals("일거", HangulConverter.convertEngToKor("dlfrj"))  // ㄺ → ㄹ 남고 ㄱ 이동
        assertEquals("읽어", HangulConverter.convertEngToKor("dlfrdj")) // 명시적 ㅇ
    }

    @Test
    fun korToEng_reverse() {
        assertEquals("skandnlzl", HangulConverter.convertKorToEng("나무위키"))
        assertEquals("dksemfhdlem", HangulConverter.convertKorToEng("안드로이드"))
        assertEquals("dkssud", HangulConverter.convertKorToEng("안녕"))
        assertEquals("gksrmf", HangulConverter.convertKorToEng("한글"))
    }

    @Test
    fun korToEng_compoundJongAndJung() {
        assertEquals("ekfr", HangulConverter.convertKorToEng("닭"))  // 복합 종성 ㄺ
        assertEquals("rkqt", HangulConverter.convertKorToEng("값"))  // 복합 종성 ㅄ
        assertEquals("dml", HangulConverter.convertKorToEng("의"))   // 복합 중성 ㅢ
        assertEquals("dho", HangulConverter.convertKorToEng("왜"))   // 복합 중성 ㅙ
    }

    @Test
    fun roundTrip_engKorEng() {
        for (s in listOf("skandnlzl", "dksemfhdlem", "dkssud", "gksrmf", "dlfjgrp")) {
            assertEquals(s, HangulConverter.convertKorToEng(HangulConverter.convertEngToKor(s)))
        }
    }

    @Test
    fun detect_hangulTyped_isHighConfidence() {
        assertTrue(HangulConverter.detectEnglishToKorean("skandnlzl") >= 0.70f)
        assertTrue(HangulConverter.detectEnglishToKorean("dksemfhdlem") >= 0.70f)
        assertTrue(HangulConverter.detectEnglishToKorean("dkssud") >= 0.70f)
        assertTrue(HangulConverter.detectEnglishToKorean("gksrmf") >= 0.70f)
        assertTrue(HangulConverter.detectEnglishToKorean("dlfjgrp") >= 0.70f)
    }

    @Test
    fun detect_englishWords_isLowConfidence() {
        // 자모로 매핑되더라도 조합 실패(낱자모)가 많아 신뢰도가 낮다.
        assertTrue(HangulConverter.detectEnglishToKorean("hello") < 0.70f)
        assertTrue(HangulConverter.detectEnglishToKorean("computer") < 0.70f)
        assertTrue(HangulConverter.detectEnglishToKorean("language") < 0.70f)
        assertTrue(HangulConverter.detectEnglishToKorean("keyboard") < 0.70f)
        assertTrue(HangulConverter.detectEnglishToKorean("function") < 0.70f)
    }

    @Test
    fun detect_empty_isZero() {
        assertEquals(0f, HangulConverter.detectEnglishToKorean(""), 0.0001f)
        assertEquals(0f, HangulConverter.detectEnglishToKorean("123 !@#"), 0.0001f)
    }

    /**
     * 자음-모음-자음 배열이 되는 흔한 영단어는 한글 음절로 100% 조합돼(the→솓, and→뭉) 조합률만
     * 보면 최고 신뢰도가 나온다 — 어휘 목록으로 억제되는지 확인(단어를 더블탭했을 때 "교체?" 칩이
     * 뜨던 오탐).
     */
    @Test
    fun detect_commonEnglishWords_suppressedToZero() {
        val words = listOf(
            "the", "and", "for", "with", "when", "then", "they", "them", "than",
            "work", "down", "such", "also", "their", "who", "did", "she", "form",
            "go", "do", "to", "so", "an", "am", "hello"
        )
        for (w in words) {
            assertEquals(w, 0f, HangulConverter.detectEnglishToKorean(w), 0.0001f)
        }
        assertEquals(0f, HangulConverter.detectEnglishToKorean("The."), 0.0001f)   // 대문자+구두점
        assertEquals(0f, HangulConverter.detectEnglishToKorean("WHEN"), 0.0001f)   // 전체 대문자
        assertEquals(0f, HangulConverter.detectEnglishToKorean("the and for"), 0.0001f) // 다중 토큰
    }

    /** 억제 목록이 진짜 한영타까지 삼키지 않는지(단음절 교정 포함). */
    @Test
    fun detect_hangulTyped_notSuppressedByStopwords() {
        for (s in listOf("dkssud", "rkawk", "dlfjgrp", "ehs", "dho", "sp")) {
            assertTrue(s, HangulConverter.detectEnglishToKorean(s) >= 0.70f)
        }
        // 상용어가 섞여 있어도 전부가 상용어는 아니므로 억제되지 않는다.
        assertTrue(HangulConverter.detectEnglishToKorean("dkssud the") > 0f)
    }
}
