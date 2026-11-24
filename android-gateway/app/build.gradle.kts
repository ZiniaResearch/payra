plugins {
    id("com.android.application") version "9.5.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("com.google.devtools.ksp") version "2.3.14"
    id("androidx.room") version "2.8.5"
}

android {
    namespace = "com.payra.gateway"

    compileSdk {
        version =
            release(37) {
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
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val roomVersion = "2.8.5"
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
}
