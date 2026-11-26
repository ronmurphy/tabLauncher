plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

android {
    namespace = "dev.tabdesk.agentcaller"
    compileSdk = 36
    defaultConfig {
        // -Ptwo: a second caller (its own package and name, logging as AgentCaller2), to test two approved apps.
        applicationId = if (project.hasProperty("two")) "dev.tabdesk.agentcaller.two" else "dev.tabdesk.agentcaller"
        resValue("string", "app_name", if (project.hasProperty("two")) "Agent caller 2 (test)" else "Agent caller (test)")
        buildConfigField("String", "TAG", if (project.hasProperty("two")) "\"AgentCaller2\"" else "\"AgentCaller\"")
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    // -Palt signs with a second, throwaway key (made on first use), to test TabDesk's certificate check.
    signingConfigs {
        create("alt") {
            storeFile = file("alt.jks"); storePassword = "agentcaller"; keyAlias = "alt"; keyPassword = "agentcaller"
        }
    }
    buildTypes {
        debug { if (project.hasProperty("alt")) signingConfig = signingConfigs.getByName("alt") }
    }
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
