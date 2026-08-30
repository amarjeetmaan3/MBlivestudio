plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.mblivestudio"
    compileSdk = 34

    defaultConfig {
        // हमने ID में '.v2' लगा दिया है ताकि यह पुराने ऐप से ना टकराए और डायरेक्ट इंस्टॉल हो जाए
        applicationId = "com.mblivestudio.v2"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "2.0.$runNumber"
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
