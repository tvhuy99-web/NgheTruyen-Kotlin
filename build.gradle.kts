import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.3.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
}

// Gradle itself may run on JDK 21, while project bytecode remains Java 17 for Android/JVM compatibility.
// Pure Kotlin/JVM modules also create compileJava tasks; pin those Java tasks to release 17 so
// Kotlin (jvmTarget 17) and Java use the same target without requiring a separately installed JDK 17.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }
    }
}
