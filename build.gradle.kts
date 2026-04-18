import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.1")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        description =
            "Scopecreep — software-defined hardware control inside JetBrains IDEs. " +
                "Drives real lab instruments (PSU, oscilloscope) through a managed Python sidecar."
    }
}
