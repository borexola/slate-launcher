import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The release key lives outside the project so it is never committed or lost with a checkout.
// Point SLATE_KEYSTORE_PROPERTIES at a file with storeFile, storePassword, keyAlias, keyPassword.
val keystorePropertiesFile = file(
    System.getenv("SLATE_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/slate-launcher/keystore.properties",
)
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}
// A relative storeFile is taken relative to the properties file, not to this build script.
val releaseStoreFile = keystoreProperties.getProperty("storeFile")?.let { path ->
    val candidate = File(path)
    if (candidate.isAbsolute) candidate else File(keystorePropertiesFile.parentFile, path)
}
val hasReleaseKey = releaseStoreFile?.isFile == true

android {
    namespace = "app.slate.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.slate.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Still installable, but signed with the shared debug key. See the README.
                val missing = when {
                    !keystorePropertiesFile.isFile -> "no $keystorePropertiesFile"
                    releaseStoreFile == null -> "no storeFile entry in $keystorePropertiesFile"
                    else -> "keystore $releaseStoreFile not found"
                }
                logger.warn("Slate: release builds will be signed with the shared DEBUG key ($missing).")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
