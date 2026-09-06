plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.langsense.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.langsense.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    // ── 고정 공유 서명키 (요청 사항: 삭제 없이 덮어쓰기 설치) ─────────────────────────
    // 모든 빌드(로컬/CI)가 같은 키로 서명되면 기존 설치 위에 바로 덮어쓰기가 된다.
    // 키스토어 경로/비밀번호는 저장소에 박지 않고 Gradle 속성(또는 환경변수 ORG_GRADLE_PROJECT_*)에서
    // 읽는다. 값이 없거나 파일이 없으면 기본 디버그 서명으로 안전하게 폴백한다(저장소에 비밀 없음).
    val sharedStoreFile = (project.findProperty("KIKI_STORE_FILE") as String?) ?: "kiki-shared.jks"
    val sharedStorePassword = project.findProperty("KIKI_STORE_PASSWORD") as String?
    val sharedKeyAlias = project.findProperty("KIKI_KEY_ALIAS") as String?
    val sharedKeyPassword = project.findProperty("KIKI_KEY_PASSWORD") as String?
    val sharedKeystore = file(sharedStoreFile)
    val useSharedSigning = sharedKeystore.exists() &&
        sharedStorePassword != null && sharedKeyAlias != null && sharedKeyPassword != null

    signingConfigs {
        if (useSharedSigning) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = sharedStorePassword
                keyAlias = sharedKeyAlias
                keyPassword = sharedKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 저사양 타겟: R8 로 미사용 코드 제거·최적화. 접근성 서비스/액티비티/JS 브리지는
            // proguard-rules.pro 의 keep 규칙으로 보호한다(래디얼 메뉴는 JS 가 문자열 이름으로
            // KikiNative.onItemTap/onDismiss/onReady 를 부르므로 난독화되면 조용히 먹통이 된다).
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (useSharedSigning) signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            isMinifyEnabled = false
            // 공유 키가 설정돼 있으면 디버그도 그 키로 서명(머신/CI 간 서명 일치 → 덮어쓰기 설치 가능).
            if (useSharedSigning) signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
}
