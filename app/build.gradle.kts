plugins {
    id("com.android.application")
}

android {
    namespace = "com.lakdoz.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lakdoz.assistant"
        minSdk = 29
        targetSdk = 35
        versionCode = 100
        versionName = "0.10.0"
    }
}
