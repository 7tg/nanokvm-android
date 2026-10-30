import java.util.Properties

plugins {
    id("com.android.application")
}

// No dependencies: framework Views, HttpURLConnection, WebView.
android {
    namespace = "dev.neo.nanokvm"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.neo.nanokvm"
        minSdk = 31
        targetSdk = 36
        // CI passes these from the release tag (v1.2.3 -> 1.2.3) and the run number.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.0.0-dev"
    }

    // Release key lives outside the repo; keystore.properties (git-ignored) points at it. CI writes it from secrets.
    // Without it, release builds use the debug key so a fresh clone still builds.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}
