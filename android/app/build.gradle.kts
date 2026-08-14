plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")

android {
    namespace = "com.usememos.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.usememos.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.32.0"
    }

    signingConfigs {
        create("release") {
            if (!keystoreFile.isNullOrEmpty()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                // PKCS12 store does not support a separate key password; it equals the store password.
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystoreFile.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(files("libs/mobile.aar"))
    implementation("net.lingala.zip4j:zip4j:2.1.1")
}