plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.blakeriding.upright.watch"
    compileSdk = 37

    defaultConfig {
        // Same as the phone app: the Wear data layer only connects apps with the same ID and signing key.
        applicationId = "com.blakeriding.upright"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    // Release key lives outside the repo: set upright.keystore and upright.keystorePassword in
    // ~/.gradle/gradle.properties. Without them, release builds fall back to the debug key.
    val releaseKey = providers.gradleProperty("upright.keystore").orNull
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey)
            storePassword = providers.gradleProperty("upright.keystorePassword").get()
            keyAlias = "upright"
            keyPassword = storePassword
        }
    }
    if (releaseKey == null) logger.warn("upright.keystore not set: release APK will be signed with the debug key")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.play.wearable)
}
