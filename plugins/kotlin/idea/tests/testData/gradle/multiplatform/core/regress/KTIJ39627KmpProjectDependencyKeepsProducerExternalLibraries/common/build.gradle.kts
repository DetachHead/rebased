plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
}

kotlin {
    androidLibrary {
        namespace = "org.example.ktij39627.common"
        compileSdk = 35
        minSdk = 23
    }

    jvm()

    sourceSets.commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    }
}
