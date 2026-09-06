# Keep the accessibility service entry point (declared in manifest).
-keep class com.langsense.app.service.LangSenseAccessibilityService { *; }

# Keep activities referenced from the manifest.
-keep class com.langsense.app.ui.** { *; }

# WebView JS 브리지(QuickMenuOverlayView.WebBridge): JS 가 메서드 이름 문자열로 호출하므로
# 이름을 보존해야 한다. (proguard-android-optimize 기본에도 있지만 의도를 명시)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 디버그 로그 제거: 플래시/감지 경로의 Log.d 문자열 템플릿이 릴리스에서 StringBuilder 를 돌리지 않게.
# (Log.w 는 서비스 생존성 진단용이라 남긴다)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
