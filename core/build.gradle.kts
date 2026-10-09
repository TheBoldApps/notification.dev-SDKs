import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.security.MessageDigest

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.openapi.generator")
    id("com.vanniktech.maven.publish")
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

mavenPublishing {
    coordinates("dev.notification", "sdk-core", "0.2.0")
}

// AGP derives baseline-profile directories from Kotlin source roots.
tasks.matching { it.name == "prepareAndroidMainArtProfile" }.configureEach {
    dependsOn(generateSdkApi)
}

// Keep a local package for development and a separate ZIP for distribution.
val stageSwiftFramework by tasks.registering(Sync::class) {
    dependsOn("assembleNotificationCoreReleaseXCFramework")
    from(layout.buildDirectory.dir("XCFrameworks/release/NotificationCore.xcframework"))
    into(rootProject.layout.projectDirectory.dir("ios/Artifacts/NotificationCore.xcframework"))
}

val swiftReleaseArchive = rootProject.layout.buildDirectory.file("NotificationCore.xcframework.zip")
val packageSwiftFramework by tasks.registering(Exec::class) {
    dependsOn(stageSwiftFramework)
    val framework = rootProject.layout.projectDirectory.dir("ios/Artifacts/NotificationCore.xcframework")
    inputs.dir(framework)
    outputs.file(swiftReleaseArchive)
    commandLine(
        "ditto", "-c", "-k", "--sequesterRsrc", "--keepParent",
        framework.asFile.absolutePath, swiftReleaseArchive.get().asFile.absolutePath,
    )
    doFirst {
        val archive = swiftReleaseArchive.get().asFile
        archive.parentFile.mkdirs()
        // ditto can update an existing ZIP; always create a fresh archive when inputs change.
        archive.delete()
    }
}

tasks.register("prepareSwiftPackage") {
    group = "distribution"
    description = "Builds the Swift release ZIP and updates the public manifest URL and checksum."
    dependsOn(packageSwiftFramework)
    doLast {
        val archive = swiftReleaseArchive.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var count = stream.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = stream.read(buffer)
            }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        val releaseVersion = project.version.toString()
        val url = "https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/$releaseVersion/NotificationCore.xcframework.zip"
        val manifest = rootProject.file("Package.swift")
        val original = manifest.readText()
        val urlPattern = Regex("""url:\s*"https://github\.com/TheBoldApps/notification\.dev-SDKs/releases/download/[^"\s]+/NotificationCore\.xcframework\.zip"""")
        val checksumPattern = Regex("""checksum:\s*"[a-f0-9]{64}"""")
        check(urlPattern.findAll(original).count() == 1 && checksumPattern.findAll(original).count() == 1) {
            "Expected one NotificationCore release URL and checksum in Package.swift; manifest was not changed."
        }
        val updated = original.replace(urlPattern, "url: \"$url\"")
            .replace(checksumPattern, "checksum: \"$checksum\"")
        if (updated != original) manifest.writeText(updated)
        logger.lifecycle("Swift release $releaseVersion ready: ${archive.absolutePath}")
        logger.lifecycle("SHA-256: $checksum")
        logger.lifecycle("Package.swift updated. Commit it with the tested sources, create tag $releaseVersion, and upload this exact ZIP to the GitHub release.")
        logger.lifecycle("Nothing was uploaded or published.")
    }
}
