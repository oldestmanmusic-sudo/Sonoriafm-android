plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Si tu app actual ya tiene otro identificador, cámbialo aquí (applicationId) para que
    // esta versión la sustituya al instalarla.
    namespace = "com.sonoriafm.radio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sonoriafm.radio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
}
