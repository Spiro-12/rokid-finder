plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.rokidfinder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.rokidfinder"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // Keep these formats uncompressed in the APK so they can be mmap'd at runtime.
        noCompress += listOf("tflite", "onnx")
    }

    // Limit native libs to the ABI the glasses use — the sherpa-onnx prebuilt
    // ships four ABIs (~140 MB total); we only need arm64-v8a.
    splits {
        abi {
            isEnable = false  // we already pruned other ABIs from jniLibs/
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // CameraX — capture frames as YUV via ImageAnalysis
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")

    // LifecycleService — lets the service own the CameraX lifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // On-device object detection (EfficientDet-Lite0 via the TFLite Task Vision lib).
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")

    // On-device offline speech recognition (Phase 4)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
