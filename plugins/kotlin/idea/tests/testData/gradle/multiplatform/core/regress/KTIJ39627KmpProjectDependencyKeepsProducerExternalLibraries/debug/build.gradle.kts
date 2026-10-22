plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
}

kotlin {
    androidLibrary {
        namespace = "org.example.ktij39627.debug"
        compileSdk = 35
        minSdk = 23
    }

    jvm()

    sourceSets.commonMain.dependencies {
        api(project(":common"))
    }
}
