plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mblivestudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mblivestudio"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.1")
}
