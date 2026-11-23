plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.slate.postercheck"
    compileSdk = 35
    defaultConfig { applicationId = "app.slate.postercheck"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") }
