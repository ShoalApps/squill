plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    // Android has its own XML parser built in; the desktop app and tests bring this one.
    compileOnly("net.sf.kxml:kxml2:2.3.0")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("junit:junit:4.13.2")
}
