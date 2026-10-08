plugins {
    id("com.android.library") version "9.1.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.1.1" apply false
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("org.openapi.generator") version "7.15.0" apply false
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
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
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
