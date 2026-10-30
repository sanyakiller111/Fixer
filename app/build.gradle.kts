import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release key from keystore.properties (local) or FIXER_KEYSTORE* env vars (CI); without it the debug key is used
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signing(prop: String, env: String): String? = keystore.getProperty(prop) ?: System.getenv(env)

android {
    namespace = "com.october.fixer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sanyakiller111.fixer"
        // Android 10+: the tun/hev socket pair needs Os.setsockoptTimeval (API 29)
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        create("release") {
            val file = signing("storeFile", "FIXER_KEYSTORE")
            if (file != null) {
                storeFile = rootProject.file(file)
                storePassword = signing("storePassword", "FIXER_KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "FIXER_KEY_ALIAS")
                keyPassword = signing("keyPassword", "FIXER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // not debuggable, so ART compiles it properly (debug builds stutter on the first animations)
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar's org.json is a stub in JVM tests
}
