plugins { id("org.jetbrains.kotlin.jvm") }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(project(":source-api"))
    implementation(project(":source-runtime"))
    implementation(project(":source-package"))
    implementation(project(":source-diagnostics"))
    implementation(project(":source-js-sandbox"))
    implementation("org.jsoup:jsoup:1.23.1")
    testImplementation(project(":source-compat-testkit"))
    testImplementation("junit:junit:4.13.2")
}

tasks.register<JavaExec>("auditCorpus") {
    group = "verification"
    description = "Audit an acquired vBook corpus and emit detector/feature-matrix JSON."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("vn.nghetruyen.source.vbook.VBookCorpusAuditMainKt")
    val corpusDir = providers.gradleProperty("vbookCorpusDir")
        .orElse(rootProject.layout.buildDirectory.dir("vbook-compat-lab/corpus/packages").map { it.asFile.absolutePath })
    val auditOut = providers.gradleProperty("vbookAuditOut")
        .orElse(rootProject.layout.buildDirectory.file("vbook-compat-lab/corpus-audit.json").map { it.asFile.absolutePath })
    args(corpusDir.get(), auditOut.get())
}
