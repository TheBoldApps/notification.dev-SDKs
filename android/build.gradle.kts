plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "dev.notification.sdk"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing { singleVariant("release") { withSourcesJar() } }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}

dependencies {
    api(project(":core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    api("com.google.firebase:firebase-messaging:25.1.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "android-sdk"
                pom {
                    name.set("notification.dev Android SDK")
                    description.set("Native notification.dev client")
                }
            }
        }
    }
}
