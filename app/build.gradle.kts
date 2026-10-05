import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

// 릴리스 서명 값은 깃에 넣지 않는 local.properties 에 둔다. gradle.properties / -P / ORG_GRADLE_PROJECT_ 환경변수도 받는다.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingProperty(key: String): String? =
    localProperties.getProperty(key)?.takeIf { it.isNotBlank() } ?: providers.gradleProperty(key).orNull

android {
    namespace = "com.galaxy.airviewdictionary"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.galaxy.airviewdictionary"
        minSdk = 26
        targetSdk = 36
        versionCode = 20806
        versionName = "2.8.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    bundle {
        abi { enableSplit = true } // ABI별로 APK를 나누기
        language { enableSplit = true } // 언어별로 APK를 나누기
        density { enableSplit = true } // 해상도별로 APK를 나누기
    }
    // 키스토어 값이 없으면(공개 미러 클론 등) 서명 없이 디버그 빌드만 되게 하고, 릴리스 빌드는 멈춘다.
    val releaseStoreFile = signingProperty("KEYSTORE_FILE")
    val releaseKeyAlias = signingProperty("KEY_ALIAS")
    val releaseKeyPassword = signingProperty("KEY_PASSWORD")
    if (releaseStoreFile != null && releaseKeyAlias != null && releaseKeyPassword != null) {
        signingConfigs.create("release") {
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = releaseKeyPassword
        }
    } else if (gradle.startParameter.taskNames.any { it.contains("Release") }) {
        throw GradleException("릴리스 서명 값 없음: local.properties 에 KEYSTORE_FILE / KEY_ALIAS / KEY_PASSWORD 를 넣을 것")
    }
    // AdMob 앱 ID 는 빌드 타입별로 둔다. 광고 단위 ID 는 코드에서 BuildConfig.DEBUG 로 고른다(AdGateActivity).
    // 예전처럼 실행한 태스크 이름으로 고르면 `assembleDebug bundleRelease` 를 한 번에 돌릴 때 릴리스에 테스트 ID 가 들어갔다.
    buildTypes {
        debug {
            manifestPlaceholders["ADMOB_APP_ID"] = "ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx"
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            manifestPlaceholders["ADMOB_APP_ID"] = "ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx"
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Crashlytics 매핑 파일 업로드는 minify 빌드에서 기본으로 켜진다(플러그인 3.0.8: getOrElse(isMinifyEnabled))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // PP-OCRv5 모델 팩. 스토어 설치에서는 Play 가 설치 직후 받아 준다(fast-follow).
    assetPacks += listOf(":paddle_models")
    // installDebug 로 까는 APK 에는 팩이 따라가지 않는다. 디버그 빌드는 같은 모델을 APK 에셋으로도 넣어
    // 기기에서 바로 돌려 볼 수 있게 한다 — 코드는 팩을 먼저 찾고 없으면 에셋을 본다(PaddleModelFiles).
    sourceSets.getByName("debug").assets.directories.add("../paddle_models/src/main/assets")
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.admob)
    implementation(libs.app.review)
//    implementation(libs.app.update)

    // Architecture Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.compose.foundation)

    implementation(libs.androidx.annotation)

    // datastore
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.android.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 우리는 WorkManager 를 직접 쓰지 않고, AdMob(play-services-ads)이 전이로 끌어온다.
    // AdMob 전이본 2.7.0 은 구식 Room(2.2.5)을 동반해 R8 최소 규칙에서 WorkDatabase 생성이 깨진다.
    // 그렇다고 2.9.0+ 로 올리면 JobScheduler.forNamespace(API34)를 호출해 일부 OEM 기기에서
    // NoSuchMethodError 로 크래시난다(2.5.2 사례). → forNamespace 이전 마지막 버전 2.8.1 로 고정하고,
    // R8 대비는 proguard 의 Room keep 규칙으로 방어한다.
    implementation(libs.androidx.work.runtime)

    // theme
    implementation(libs.material)
    // icons
    implementation(libs.material.icons.extended)

    // PP-OCRv5 추론(ML Kit 이 없는 문자권: 아랍·키릴·태국). 출시 APK 에 들어간다. 설치 크기 arm64 +17.6MB, 내려받기 약 +6.3MB
    implementation(libs.onnxruntime.android)
    // PP-OCRv5 모델 팩(:paddle_models, fast-follow)을 받는다.
    implementation(libs.play.asset.delivery)

    // OCR: 언번들(GMS) 버전 — OCR 모델을 앱에 내장하지 않고 Play 서비스가 런타임에 제공해 앱 크기를 줄인다.
    // 모델은 App.onCreate 에서 ModuleInstallClient 로 첫 실행 시 미리 내려받는다(첫 OCR 지연 방지).
    implementation(libs.google.gms.mlkit.text.recognition)
    implementation(libs.google.gms.mlkit.text.recognition.chinese)
    implementation(libs.google.gms.mlkit.text.recognition.devanagari)
    implementation(libs.google.gms.mlkit.text.recognition.japanese)
    implementation(libs.google.gms.mlkit.text.recognition.korean)
    implementation(libs.google.mlkit.language.id)

    implementation(libs.deepl.api)

    // firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.appcheck)
    implementation(libs.firebase.appcheck.playintegrity)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.config)

    implementation(libs.gson)
    implementation(libs.squareup.retrofit2.retrofit)
    implementation(libs.squareup.retrofit2.converter.gson)
    implementation(libs.squareup.okhttp)
    implementation(libs.reorderable)

    implementation(libs.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.monitor)
}










