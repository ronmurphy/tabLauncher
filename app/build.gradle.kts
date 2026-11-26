import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// TabDesk's own release key lives in signing/ (kept out of git; back it up, since updates must be signed with it).
// Without it, release builds fall back to this computer's debug key.
val signingDir = rootProject.file("signing")
val releaseKey = Properties().apply {
    rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "dev.tabdesk"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tabdesk"
        minSdk = 31
        targetSdk = 35
        // Bump for each release. Android needs a number that only goes up: 1.2.3 → 10203.
        versionName = "1.4.0"
        versionCode = versionName!!.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }
    }

    signingConfigs {
        if (releaseKey.isNotEmpty()) create("release") {
            storeFile = File(signingDir, releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Agent control's Binder interface (src/main/aidl, as docs/agent-control.md gives it to calling apps).
    buildFeatures { aidl = true }
    packaging { resources.excludes += "/META-INF/*.version" }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    lint { checkReleaseBuilds = false }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Copies installed before TabDesk had its own key were signed with the debug key. signing/lineage.bin is Android's
// proof that the debug key handed over to the release key, so those copies can update in place and keep their data.
// AGP can't add it itself, so the finished APK is signed again with it.
val lineageSign = tasks.register("signReleaseWithLineage") {
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val lineage = File(signingDir, "lineage.bin")
    onlyIf { releaseKey.isNotEmpty() && lineage.exists() }
    doLast {
        val tools = File(android.sdkDirectory, "build-tools/${android.buildToolsVersion}")
        val proc = ProcessBuilder(
            File(tools, "apksigner").path, "sign",
            "--ks", File(signingDir, releaseKey.getProperty("storeFile")).path,
            "--ks-key-alias", releaseKey.getProperty("keyAlias"),
            "--ks-pass", "env:TABDESK_KS_PASS",
            "--lineage", lineage.path,
            "--rotation-min-sdk-version", "31",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
            apk.get().asFile.path,
        ).redirectErrorStream(true)
        proc.environment()["TABDESK_KS_PASS"] = releaseKey.getProperty("storePassword")
        val p = proc.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "apksigner failed:\n$out" }
        File(apk.get().asFile.path + ".idsig").delete()   // AGP's v4 signature belongs to the APK as it was before

    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(lineageSign) }
