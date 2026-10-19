plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.techwithkaushik.termuxxwayland.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.techwithkaushik.termuxxwayland.companion"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-alpha01"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
