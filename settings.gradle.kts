pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Adding github packages if needed by Light SDK
        maven {
            name = "GitHubPackages-Keyboard"
            url = uri("https://maven.pkg.github.com/lightphone/light-keyboard")
            credentials {
                val ghUsername = System.getenv("GH_PACKAGES_USER")
                val ghPassword = System.getenv("GH_PACKAGES_TOKEN")
                username = ghUsername
                password = ghPassword
            }
        }
    }
}

rootProject.name = "Beeper4LightOS"

includeBuild("../light-sdk")
includeBuild("../light-sdk/plugin")

include(":app")
include(":appcontext")
