plugins {
    id("com.android.application") version "9.2.1"
}

android {
    namespace = "com.tyranor.runtime.example"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tyranor.runtime.example"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
