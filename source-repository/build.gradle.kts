plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":source-api"))
    implementation(project(":source-package"))
    implementation(project(":source-diagnostics"))
    implementation(project(":source-store"))
    implementation(project(":source-vbook"))
    testImplementation("junit:junit:4.13.2")
}
