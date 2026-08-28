// Top-level build file where you can add configuration options common to all sub-projects/modules.
// AGP 9 起内置 Kotlin 编译支持，不再需要 org.jetbrains.kotlin.android 插件
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
