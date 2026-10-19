import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val devSigningPropertiesFile = rootProject.file("build/signing/key.properties")
val devSigningProperties = Properties().apply {
    if (devSigningPropertiesFile.isFile) {
        devSigningPropertiesFile.inputStream().use { load(it) }
    }
}
val signingStoreFile = System.getenv("SIGNING_KEY_STORE")?.takeIf { it.isNotBlank() }
    ?: devSigningProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    ?: devSigningProperties.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: devSigningProperties.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: devSigningProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }
val hasDevSigning = signingStoreFile != null &&
    signingKeyAlias != null &&
    signingKeyPassword != null &&
    signingStorePassword != null &&
    rootProject.file(signingStoreFile).isFile

android {
    namespace = "org.techwithkaushik.termuxxwayland"
    compileSdk = 35

    signingConfigs {
        if (hasDevSigning) {
            create("dev") {
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingStorePassword!!
                keyAlias = signingKeyAlias!!
                keyPassword = signingKeyPassword!!
            }
        }
    }

    defaultConfig {
        applicationId = "org.techwithkaushik.termuxxwayland"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-alpha01"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (hasDevSigning) signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
