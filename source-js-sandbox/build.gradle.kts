plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.mozilla:rhino:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
