pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Allows building against a locally published karoo-ext
        // (./gradlew :lib:publishToMavenLocal in a karoo-ext checkout),
        // which avoids needing a GitHub Packages token.
        mavenLocal()
        google()
        mavenCentral()
        // karoo-ext is public but GitHub Packages always requires authentication.
        // Provide gpr.user / gpr.key in local.properties or ~/.gradle/gradle.properties.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("USERNAME") ?: "")
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("TOKEN") ?: "")
            }
        }
    }
}

rootProject.name = "karoo-classified"
include(":app", ":probe")
