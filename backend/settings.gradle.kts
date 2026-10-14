pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    // PREFER_SETTINGS：优先使用 settings 声明的仓库；允许外部（如 init 脚本）追加项目级仓库
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "animeko-web"
