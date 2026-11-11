plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.lumen.forja"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lumen.forja"
        minSdk = 23
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Même clé que Lumen TV : les mises à jour s'installent par-dessus sans désinstaller.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("app/lumen-release.jks")
            storePassword = "lumentv2026"
            keyAlias = "lumen"
            keyPassword = "lumentv2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
