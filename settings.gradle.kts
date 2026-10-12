pluginManagement {
    repositories {

        // Mirror برای Google Maven
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
        }

        // Mirror عمومی Maven
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }

        // Mirror پلاگین‌های Gradle
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
        }

        // مخزن‌های اصلی هم به عنوان پشتیبان می‌مانند
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}


plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}


dependencyResolutionManagement {

    repositoriesMode.set(
        RepositoriesMode.FAIL_ON_PROJECT_REPOS
    )

    repositories {

        // Mirror کتابخانه‌های Android
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
        }

        // Mirror کتابخانه‌های عمومی
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }

        google()
        mavenCentral()
    }
}


rootProject.name = "HTNRobotAssistant"

include(":app")