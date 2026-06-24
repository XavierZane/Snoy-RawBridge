package com.rawbridge.app

enum class ThemeMode(
    val label: String,
) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色");

    companion object {
        fun fromStorage(value: String?): ThemeMode {
            return entries.firstOrNull { it.name == value } ?: System
        }
    }
}
