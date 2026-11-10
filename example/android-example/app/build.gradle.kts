import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

val localConfig = Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.exists()) configFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.notification.example"
    compileSdk = 36
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "dev.notification.example"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
        buildConfigField("String", "NOTIFICATION_PROJECT_ID", "\"${localConfig.getProperty("notificationProjectId", "YOUR_PUBLIC_PROJECT_UUID")}\"")
        buildConfigField("String", "NOTIFICATION_BASE_URL", "\"${localConfig.getProperty("notificationBaseUrl", "https://app.notification.dev/")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation("dev.notification:android-sdk:0.3.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
