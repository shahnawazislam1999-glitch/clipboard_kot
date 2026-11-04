plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pinclip.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pinclip.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // One fixed key so every GitHub build is signed the same -> APK updates install over the old one
    // and Android keeps the Accessibility permission enabled.
    signingConfigs {
        create("fixed") {
            storeFile = file("pinclip.keystore")
            storePassword = "pinclip123"
            keyAlias = "pinclip"
            keyPassword = "pinclip123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
