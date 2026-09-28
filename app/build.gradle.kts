import java.net.URI

plugins {
    id("com.android.application")
}

android {
    namespace = "com.caughtlackin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.caughtlackin"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    buildFeatures {
        viewBinding = true
    }

    // MediaPipe mmaps models, so keep them uncompressed.
    androidResources {
        noCompress += "task"
    }
}

val downloadModels = tasks.register("downloadModels") {
    val assets = layout.projectDirectory.dir("src/main/assets").asFile
    val models = mapOf(
        "face_landmarker.task" to "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task",
        "pose_landmarker_lite.task" to "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
    )
    outputs.files(models.keys.map { File(assets, it) })
    doLast {
        assets.mkdirs()
        models.forEach { (name, url) ->
            val file = File(assets, name)
            if (!file.exists()) {
                URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadModels) }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.35")
}
