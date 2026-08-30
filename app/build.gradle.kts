plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.mblivestudio.v2"
    compileSdk = 34

    // STATIC KEYSTORE CONFIGURATION
    signingConfigs {
        create("release") {
            keyAlias = "mblive"
            keyPassword = "mblivepassword"
            storePassword = "mblivepassword"
        }
    }

    defaultConfig {
        applicationId = "com.mblivestudio.v2"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "2.0.$runNumber"
    }

    buildTypes {
        getByName("debug") {
            // signingConfig = signingConfigs.getByName("release") 
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
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.1")
}
