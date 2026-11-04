plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.shoalapps.squill"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.shoalapps.squill"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        resValue("string", "app_name", "Squill")
    }

    // The release key lives only in GitHub Actions secrets, never in the repo.
    val releaseKey = System.getenv("SHOAL_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey
                storePassword = System.getenv("SHOAL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHOAL_KEY_ALIAS")
                keyPassword = System.getenv("SHOAL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Squill Dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
}
