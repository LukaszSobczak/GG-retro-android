import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0"

// Release signing: keystore.properties (local) or env variables (CI secrets). Falls back to debug signing.
val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "pl.ggretro.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.ggretro.android"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion.split('.').map { it.toIntOrNull() ?: 0 }
            .let { (it + listOf(0, 0, 0)).take(3) }
            .let { (maj, min, patch) -> maj * 10000 + min * 100 + patch }
        versionName = appVersion
    }

    signingConfigs {
        val storeFile = signingValue("storeFile", "GG_KEYSTORE_FILE")
        if (storeFile != null && file(storeFile).isFile) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = signingValue("storePassword", "GG_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GG_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Original GG 6.1 emoticons, sounds and skin bitmaps, extracted at build time from the installer
// published in webs1821/gg-retro (pinned commit + SHA-256). Not committed to this repository.
val extractGgAssets = tasks.register<ExtractGgAssetsTask>("extractGgAssets") {
    installerUrl.set(
        "https://raw.githubusercontent.com/webs1821/gg-retro/4d5b13b814a51ee9a54037a2ae0b9e27eebf7764/server/static/gg61.exe",
    )
    installerSha256.set("bcc8157aa6bface009d8018c308bf3cef8725546b4f826bdbaf6bbeaa953b06f")
    (findProperty("ggInstaller") as String?)?.let { localInstaller.set(file(it)) }
    cacheDir.set(File(gradle.gradleUserHomeDir, "caches/gg-retro"))
    assetsDir.set(layout.buildDirectory.dir("generated/gg/assets"))
    resDir.set(layout.buildDirectory.dir("generated/gg/res"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(extractGgAssets, ExtractGgAssetsTask::assetsDir)
        variant.sources.res?.addGeneratedSourceDirectory(extractGgAssets, ExtractGgAssetsTask::resDir)
    }
}

dependencies {
    implementation("pl.ggretro:gg-protocol")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
