pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NgheTruyenKotlin"
include(
    ":app", ":source-api", ":source-package", ":source-store", ":source-runtime",
    ":source-diagnostics", ":source-network", ":source-repository", ":source-vbook", ":source-lua",
    ":source-js-sandbox", ":source-compat-testkit",
)
