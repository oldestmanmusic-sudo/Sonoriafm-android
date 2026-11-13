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
        // El número de versión lo pone GitHub en cada compilación (-Pvc=N); la app lo usa para saber si hay una nueva.
        val vc = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 1
        versionCode = vc
        versionName = "1.$vc"
    }

    // Clave de firma fija: así cada versión nueva se instala encima de la anterior.
    signingConfigs {
        create("sonoria") {
            storeFile = file("sonoria-release.keystore")
            storePassword = "sonoria2026"
            keyAlias = "sonoria"
            keyPassword = "sonoria2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sonoria")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sonoria")
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
