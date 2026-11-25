plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val baseVersion = rootProject.file("../VERSION").readText().trim()
require(baseVersion.matches(Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")))
val ciBuildCode = providers.environmentVariable("SURSOUND_BUILD_CODE").orNull?.toInt()
val ciBuildName = providers.environmentVariable("SURSOUND_BUILD_NAME").orNull
require((ciBuildCode == null) == (ciBuildName == null)) { "Incomplete CI version metadata" }
require(ciBuildCode == null || (ciBuildCode > 1 && ciBuildName == "$baseVersion-dev.$ciBuildCode")) {
    "Invalid CI version metadata"
}

android {
    namespace = "com.naos.sursound"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naos.sursound"
        minSdk = 29
        targetSdk = 35
        versionCode = ciBuildCode ?: 1
        versionName = ciBuildName ?: "$baseVersion-dev.local"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}
