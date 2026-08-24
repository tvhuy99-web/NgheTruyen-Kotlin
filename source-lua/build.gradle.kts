plugins { id("org.jetbrains.kotlin.jvm") }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(project(":source-api"))
    implementation(project(":source-package"))
    implementation(project(":source-vbook"))
    implementation("org.luaj:luaj-jse:3.0.1")
    testImplementation(project(":source-runtime"))
    testImplementation("junit:junit:4.13.2")
}
