plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.penumbraos.systeminjector"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("abxdroppedapk.keystore")
            storePassword = "abxdroppedapk"
            keyAlias = "abxdroppedapk"
            keyPassword = "abxdroppedapk"
        }
    }

    defaultConfig {
        applicationId = "com.penumbraos.systeminjector"
        minSdk = 31
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":common"))
    // ManifestEditor for binary AndroidManifest.xml patching
    implementation("com.github.WindySha:ManifestEditor:2.0")
    // apksig for APK signing
    implementation("com.android.tools.build:apksig:8.7.3")
}
