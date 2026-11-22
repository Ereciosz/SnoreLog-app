plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.snorelog"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.snorelog"
        minSdk = 29
        targetSdk = 35
        versionCode = 47
        versionName = "0.47"
    }
    signingConfigs {
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.google.guava:guava:33.3.1-android")
}
