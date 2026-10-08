import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.openapi.generator")
    `maven-publish`
}

val generateSdkApi by tasks.registering(org.openapitools.generator.gradle.plugin.tasks.GenerateTask::class) {
    generatorName.set("kotlin")
    library.set("multiplatform")
    inputSpec.set(rootProject.file("contract/openapi.json").path)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.path)
    packageName.set("dev.notification.sdk.generated")
    modelPackage.set("dev.notification.sdk.generated.model")
    apiPackage.set("dev.notification.sdk.generated.api")
    globalProperties.set(mapOf(
        "models" to "", "apis" to "NativeSDK", "modelDocs" to "false", "modelTests" to "false",
        "apiDocs" to "false", "apiTests" to "false", "supportingFiles" to ""
    ))
    configOptions.set(mapOf("nonPublicApi" to "true", "dateLibrary" to "string", "enumPropertyNaming" to "UPPERCASE"))
    typeMappings.set(mapOf("UUID" to "kotlin.String", "URI" to "kotlin.String"))
    schemaMappings.set(mapOf(
        "CommonScalar" to "kotlinx.serialization.json.JsonPrimitive",
        "JsonValue" to "kotlinx.serialization.json.JsonElement",
        "CommonIdempotency" to "kotlinx.serialization.json.JsonObject"
    ))
    doFirst { delete(outputDir.get()) }
}

kotlin {
    android {
        namespace = "dev.notification.sdk.core"
        compileSdk = 36
        minSdk = 23
        withHostTestBuilder {}
    }

    val framework = XCFramework("NotificationCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "NotificationCore"
            isStatic = true
            framework.add(this)
        }
    }

    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvmToolchain(17)

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSdkApi.map { file(it.outputDir.get()).resolve("src/commonMain/kotlin") })
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation("io.ktor:ktor-client-core:3.1.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation("io.ktor:ktor-client-mock:3.1.3")
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.1.3") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.1.3") }
        named("androidHostTest") {
            resources.srcDir("../contract")
            dependencies { implementation("com.squareup.okhttp3:mockwebserver:4.12.0") }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactId.replace("core", "sdk-core")
    }
}

// AGP derives baseline-profile directories from Kotlin source roots.
tasks.matching { it.name == "prepareAndroidMainArtProfile" }.configureEach {
    dependsOn(generateSdkApi)
}

// Stage the binary inside the local Swift package; binaries remain untracked.
tasks.register<Sync>("prepareSwiftPackage") {
    dependsOn("assembleNotificationCoreReleaseXCFramework")
    from(layout.buildDirectory.dir("XCFrameworks/release/NotificationCore.xcframework"))
    into(rootProject.layout.projectDirectory.dir("ios/Artifacts/NotificationCore.xcframework"))
}
