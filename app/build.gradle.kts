plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "br.com.copiloto"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.copiloto"
        minSdk = 26
        targetSdk = 34
        versionCode = build
        versionName = "1.0.$build"
    }

    // Chave fixa: assim cada APK novo instala por cima do anterior sem desinstalar.
    signingConfigs {
        create("fixa") {
            storeFile = file("copiloto.keystore")
            storePassword = "copiloto"
            keyAlias = "copiloto"
            keyPassword = "copiloto"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
        debug { signingConfig = signingConfigs.getByName("fixa") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
