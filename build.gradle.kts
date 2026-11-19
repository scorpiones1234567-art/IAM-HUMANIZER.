plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.iam.humanizer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.iam.humanizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
