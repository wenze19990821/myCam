plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.myCam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.myCam"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "Mystery"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") 
            
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
