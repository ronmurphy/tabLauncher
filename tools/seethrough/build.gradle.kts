plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

android {
    namespace = "dev.tabdesk.seethrough"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tabdesk.seethrough"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
