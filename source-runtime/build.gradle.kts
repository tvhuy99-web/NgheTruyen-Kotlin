plugins { id("org.jetbrains.kotlin.jvm") }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(project(":source-api"))
    implementation(project(":source-diagnostics"))
    implementation("org.jsoup:jsoup:1.23.1")
    testImplementation("junit:junit:4.13.2")
}
