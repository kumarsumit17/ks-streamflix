package com.ks.streamflix.client.settings

/** Saved settings: SharedPreferences on Android, java.util.prefs on desktop. */
interface SettingsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** An in-memory store, for tests. */
class MemorySettings : SettingsStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** The suite's language rule: follow the system, allow a choice, remember it. */
enum class LanguageChoice(val saved: String) {
    System("system"), English("en"), Hindi("hi");

    companion object {
        /** Anything unknown in the saved setting counts as "Same as the system". */
        fun parse(saved: String?): LanguageChoice = entries.firstOrNull { it.saved == saved } ?: System
    }
}

/** The language to show: the choice, else the first supported system language ([preferred], BCP 47), else English. */
fun resolveLanguage(choice: LanguageChoice, preferred: List<String>): String = when (choice) {
    LanguageChoice.English -> "en"
    LanguageChoice.Hindi -> "hi"
    LanguageChoice.System -> preferred.map { it.substringBefore('-').substringBefore('_').lowercase() }
        .firstOrNull { it == "en" || it == "hi" } ?: "en"
}

object SettingKeys {
    const val SERVER = "server"
    const val TOKEN = "token"
    const val USER = "user"
    const val LANGUAGE = "language"
}
