import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val updaterVersionName = System.getenv("WEAR_UPDATER_VERSION_NAME") ?: "1.0.0"
val updaterVersionCode = System.getenv("WEAR_UPDATER_VERSION_CODE")?.toInt()
    ?: updaterVersionName.split('.').map(String::toInt).let { (major, minor, patch) ->
        major * 1_000_000 + minor * 1_000 + patch
    }
val uploadStoreFile = System.getenv("WEAR_UPDATER_STORE_FILE")

android {
    namespace = "com.ivanmalison.wearupdater"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ivanmalison.wearupdater"
        minSdk = 26
        targetSdk = 36
        versionCode = updaterVersionCode
        versionName = updaterVersionName
    }

    signingConfigs {
        if (uploadStoreFile != null) {
            create("release") {
                storeFile = file(uploadStoreFile)
                storePassword = System.getenv("WEAR_UPDATER_STORE_PASSWORD")
                keyAlias = System.getenv("WEAR_UPDATER_KEY_ALIAS")
                keyPassword = System.getenv("WEAR_UPDATER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (uploadStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("Release keystore is unset; using the debug signer for this local build.")
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
