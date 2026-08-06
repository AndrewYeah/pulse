pluginManagement {
    repositories {
        val runningInCi = System.getenv("CI").equals("true", ignoreCase = true)
        if (runningInCi) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
            gradlePluginPortal()
        } else {
            // 国内开发环境优先使用阿里云镜像，官方源作为兜底。
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
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
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val runningInCi = System.getenv("CI").equals("true", ignoreCase = true)
        if (runningInCi) {
            google()
            mavenCentral()
        } else {
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            google()
            mavenCentral()
        }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Pulse"
include(":app")
