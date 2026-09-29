// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    dependencies {
        // AGP 내장 Kotlin 은 AGP 가 물고 오는 KGP(9.4.1 도 2.2.10)로 컴파일한다. 카탈로그의 kotlin 버전으로 올린다
        // (Compose 컴파일러 플러그인·Dagger 가 같은 버전을 기대). AGP 를 올리면 이 줄이 필요한지 다시 볼 것.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.google.firebase.crashlytics) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.asset.pack) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.compose.compiler) apply false
}
