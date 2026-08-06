plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":source-api"))
    testImplementation("junit:junit:4.13.2")
}
