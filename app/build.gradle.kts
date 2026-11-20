
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
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")

    // Codificador de audio MP3
    implementation("net.qiujuer.lame:lame:1.0.0")

    // Carátulas y metadatos ID3 para MP3
    implementation("com.mpatric:mp3agic:0.9.1")
}
