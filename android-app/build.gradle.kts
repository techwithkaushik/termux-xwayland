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
val signingStorePath = System.getenv("SIGNING_KEY_STORE")?.takeIf { it.isNotBlank() }
val signingStoreFile = signingStorePath?.let { rootProject.file(it) }
    ?: devSigningProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
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
    signingStoreFile.isFile

if (signingStorePath != null && !hasDevSigning) {
    error("SIGNING_KEY_STORE was provided, but the shared development signing configuration is incomplete or the keystore file is missing.")
}

android {
    namespace = "org.techwithkaushik.termuxxwayland"
    compileSdk = 35

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    signingConfigs {
        if (hasDevSigning) {
            create("dev") {
                storeFile = signingStoreFile!!
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
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
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

    externalNativeBuild {
        cmake { version = "3.22.1" }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    testImplementation("junit:junit:4.13.2")
}
