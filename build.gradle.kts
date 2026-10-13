import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    google()
    intellijPlatform {
        defaultRepositories()
    }
}

// A local Android Studio is used when there is one at androidStudioPath (fast, no download); otherwise, as on CI,
// the same version is downloaded.
val localAndroidStudio = providers.gradleProperty("androidStudioPath").orNull?.takeIf { it.isNotBlank() && file(it).isDirectory }

dependencies {
    intellijPlatform {
        if (localAndroidStudio != null) local(localAndroidStudio) else androidStudio(providers.gradleProperty("androidStudioVersion"))
        bundledPlugins("org.jetbrains.android", "Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // -PsandboxDir=<dir> runs the IDE on a separate sandbox, e.g. while another sandbox IDE is still open.
    providers.gradleProperty("sandboxDir").orNull?.let { sandboxContainer = layout.projectDirectory.dir(it) }

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false

    // Only needed for the JetBrains Marketplace; buildPlugin works without any of these. On CI an unset secret
    // arrives as an empty variable, which must count as "not configured" so the plugin is simply left unsigned.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN").filter { it.isNotBlank() }
        privateKey = providers.environmentVariable("PRIVATE_KEY").filter { it.isNotBlank() }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD").filter { it.isNotBlank() }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN").filter { it.isNotBlank() }
    }
}

tasks.test {
    // Component tests build Swing components; CI machines have no display.
    systemProperty("java.awt.headless", "true")
}
