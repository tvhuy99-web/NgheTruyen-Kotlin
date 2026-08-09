plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.mozilla:rhino:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
