plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.mblivestudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mblivestudio"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.1")
}
