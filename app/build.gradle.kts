import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Optional private release key. Without it, release builds use the committed debug key.
val signingPropsFile = rootProject.file("signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) load(signingPropsFile.inputStream())
}

android {
    namespace = "com.way.facebiometricfix"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.way.facebiometricfix"
        minSdk = 35
        targetSdk = 36
        versionCode = 5
        versionName = "2.2"

    }

    signingConfigs {
        // AOSP's public test key (build/target/product/security/testkey), committed so
        // every build (local or CI) has the same signature and updates install over each other.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file(signingProps.getProperty("storeFile", "release.jks"))
            storePassword = signingProps.getProperty("storePassword", "")
            keyAlias = signingProps.getProperty("keyAlias", "")
            keyPassword = signingProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(
                if (signingPropsFile.exists()) "release" else "debug"
            )
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
