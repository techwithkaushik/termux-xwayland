plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val devSigningPropertiesFile = rootProject.file("build/signing/key.properties")
val devSigningProperties = Properties().apply {
    if (devSigningPropertiesFile.isFile) {
        devSigningPropertiesFile.inputStream().use { load(it) }
    }
}
val hasDevSigning = devSigningPropertiesFile.isFile

android {
    namespace = "org.techwithkaushik.termuxxwayland"
    compileSdk = 35

    signingConfigs {
        if (hasDevSigning) {
            create("dev") {
                storeFile = rootProject.file(devSigningProperties.getProperty("storeFile"))
                storePassword = devSigningProperties.getProperty("storePassword")
                keyAlias = devSigningProperties.getProperty("keyAlias")
                keyPassword = devSigningProperties.getProperty("keyPassword")
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
