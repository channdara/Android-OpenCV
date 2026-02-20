plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mastertipsy.androidopencv"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "com.mastertipsy.androidopencv"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

//noinspection UseTomlInstead
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Camera library (version 1.4.2 is the last version that support minSdk 21)
    // MVN: https://mvnrepository.com/artifact/androidx.camera/camera-camera2
    val cameraVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-extensions:$cameraVersion")

    // ExifInterface library
    // MVN: https://mvnrepository.com/artifact/androidx.exifinterface/exifinterface
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // OpenCV library
    // MVN: https://mvnrepository.com/artifact/org.opencv/opencv
    implementation("org.opencv:opencv:4.13.0")
}