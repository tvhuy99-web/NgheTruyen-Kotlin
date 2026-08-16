import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val diagnosticBuildId = providers.environmentVariable("GITHUB_SHA")
    .orElse(providers.environmentVariable("NGHETRUYEN_BUILD_SHA"))
    .orElse("local")
    .get()
    .replace(Regex("[^A-Za-z0-9._-]"), "_")
    .take(80)

val stableDebugKeystoreB64 = rootProject.file(".github/signing/stable-debug.keystore.b64")
val stableDebugKeystore = rootProject.file(".gradle/nghetruyen-stable-debug.keystore")
check(stableDebugKeystoreB64.isFile) {
    "Missing stable debug signing key: ${stableDebugKeystoreB64.path}"
}
if (!stableDebugKeystore.isFile) {
    stableDebugKeystore.parentFile.mkdirs()
    stableDebugKeystore.writeBytes(
        Base64.getMimeDecoder().decode(stableDebugKeystoreB64.readText().trim()),
    )
}

android {
    namespace = "vn.nghetruyen.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.nghetruyen.app"
        minSdk = 26
        targetSdk = 36
        
        
        versionCode = 36
        versionName = "3.0.2"
        buildConfigField("String", "DIAGNOSTIC_BUILD_ID", "\"$diagnosticBuildId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.schemaLocation" to "$projectDir/schemas",
                    "room.incremental" to "true",
                )
            }
        }
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = stableDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }

        val storePath = providers.environmentVariable("NGHETRUYEN_RELEASE_STORE_FILE").orNull
        val storePasswordValue = providers.environmentVariable("NGHETRUYEN_RELEASE_STORE_PASSWORD").orNull
        val keyAliasValue = providers.environmentVariable("NGHETRUYEN_RELEASE_KEY_ALIAS").orNull
        val keyPasswordValue = providers.environmentVariable("NGHETRUYEN_RELEASE_KEY_PASSWORD").orNull
        if (!storePath.isNullOrBlank() && !storePasswordValue.isNullOrBlank() &&
            !keyAliasValue.isNullOrBlank() && !keyPasswordValue.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(storePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        sarifReport = true
        textReport = true
    }

    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

dependencies {
    implementation(project(":source-api"))
    implementation(project(":source-package"))
    implementation(project(":source-store"))
    implementation(project(":source-runtime"))
    implementation(project(":source-diagnostics"))
    implementation(project(":source-network"))
    implementation(project(":source-repository"))
    implementation(project(":source-vbook"))
    implementation(project(":source-lua"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime:2.11.2")

    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("org.jsoup:jsoup:1.23.1")
    implementation("co.ntbl:lame:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    
    
    testImplementation("org.json:json:20251224")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails unless all release signing environment variables are configured."
    doLast {
        val required = listOf(
            "NGHETRUYEN_RELEASE_STORE_FILE",
            "NGHETRUYEN_RELEASE_STORE_PASSWORD",
            "NGHETRUYEN_RELEASE_KEY_ALIAS",
            "NGHETRUYEN_RELEASE_KEY_PASSWORD",
        )
        val missing = required.filter { System.getenv(it).isNullOrBlank() }
        check(missing.isEmpty()) { "Thiếu biến ký release: ${missing.joinToString()}" }
        check(file(System.getenv("NGHETRUYEN_RELEASE_STORE_FILE")).isFile) { "Không tìm thấy keystore release." }
    }
}
