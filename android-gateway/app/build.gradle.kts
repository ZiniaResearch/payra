plugins {
    id("com.android.application") version "9.5.2"
}

android {
    namespace = "com.payra.gateway"

    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.payra.gateway"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
}
