plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":source-api"))
    implementation(project(":source-runtime"))
    implementation(project(":source-package"))
    implementation(project(":source-diagnostics"))
    implementation("org.mozilla:rhino:1.9.1")
    implementation("org.jsoup:jsoup:1.23.1")
    testImplementation("junit:junit:4.13.2")
}
