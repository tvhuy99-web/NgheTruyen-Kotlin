plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":source-api"))
    implementation(project(":source-diagnostics"))
    implementation("org.jsoup:jsoup:1.23.1")
    testImplementation("junit:junit:4.13.2")
}
