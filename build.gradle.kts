plugins {
    id("com.android.application") version "8.13.2"
}

val androidKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.alarmbot.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alarmbot.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        androidKeystorePath?.let { keyPath ->
            create("stableDebug") {
                storeFile = file(keyPath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            androidKeystorePath?.let {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }

        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
