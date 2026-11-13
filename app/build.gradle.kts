plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PbuildNumber=${{ github.run_number }}. Local builds get 1.
val buildNumber: Int = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "io.github.ghghuser.correctmode"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ghghuser.correctmode"
        minSdk = 34
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
    }

    // Fixed DEBUG-ONLY keystore (public password "android"). It is committed on purpose so that
    // every CI build is signed with the same key and can be installed over the previous one.
    // Never use this for a release/Play build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}
