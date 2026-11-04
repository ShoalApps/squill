import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation("net.sf.kxml:kxml2:2.3.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "io.github.shoalapps.squill.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Squill"
            packageVersion = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
            description = "Notes, drawings, paintings and slides, as files on your computer"
            vendor = "Shoal"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.instrument", "jdk.unsupported")
            linux { iconFile.set(project.file("icon.png")); menuGroup = "Office" }
            windows { iconFile.set(project.file("icon.ico")); menu = true; shortcut = true }
            macOS { iconFile.set(project.file("icon.icns")) }
        }
    }
}
