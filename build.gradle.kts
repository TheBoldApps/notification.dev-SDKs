plugins {
    id("com.android.library") version "9.1.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.1.1" apply false
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("org.openapi.generator") version "7.15.0" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "dev.notification"
    version = "0.1.0"
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    developers {
                        developer {
                            id.set("TheBoldApps")
                            name.set("The Bold Apps")
                            url.set("https://theboldapps.com")
                            email.set(providers.gradleProperty("POM_DEVELOPER_EMAIL"))
                            organization.set("The Bold Apps, LLC")
                            organizationUrl.set("https://theboldapps.com")
                        }
                    }
                }
            }
        }
    }

    // Module-specific paths prevent notice collisions when consuming both SDK artifacts.
    val licenseDirectory = "META-INF/dev.notification/${project.name}"
    tasks.withType<Zip>().configureEach {
        from(rootProject.files("LICENSE", "NOTICE")) {
            into(licenseDirectory)
        }
    }
}

tasks.register("verifyRelease") {
    group = "verification"
    description = "Runs Android unit tests, release lint, shared-core tests, and core POM validation."
    // Android library unit tests use the debug variant by default; no testReleaseUnitTest exists.
    dependsOn(":android:testDebugUnitTest", ":android:lintRelease", ":core:allTests")
    dependsOn(
        ":core:checkPomFileForAndroidPublication",
        ":core:checkPomFileForIosArm64Publication",
        ":core:checkPomFileForIosSimulatorArm64Publication",
        ":core:checkPomFileForKotlinMultiplatformPublication",
    )
}
