plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.automat"
    compileSdk = 35

    defaultConfig {
        applicationId = "dk.automat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Standard-config i appen = kaffeautomat-eksemplet (examples/kaffeautomat/config.json).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/exampleAssets"))
}

val copyExampleConfig by tasks.registering(Copy::class) {
    from(rootProject.file("../examples/kaffeautomat/config.json"))
    into(layout.buildDirectory.dir("generated/exampleAssets"))
}
tasks.named("preBuild") { dependsOn(copyExampleConfig) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // Androids org.json er kun en stub i unit tests – brug den rigtige.
    testImplementation("org.json:json:20240303")
}
