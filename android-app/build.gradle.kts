plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.techwithkaushik.termuxxwayland"
    compileSdk = 35
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
