package com.ks.streamflix.server.lang

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Language(val code: String) {
    ENGLISH("en"), HINDI("hi");

    companion object {
        fun ofCode(code: String?): Language? = entries.firstOrNull { it.code == code?.lowercase() }

        /**
         * The suite's language rule for a browser: a saved choice wins; otherwise the first of the
         * browser's languages ([acceptLanguage]) that is supported; otherwise English.
         */
        fun resolve(choice: String?, acceptLanguage: String?): Language {
            ofCode(choice)?.let { return it }
            val preferred = acceptLanguage.orEmpty().split(',')
                .map { it.substringBefore(';').trim().substringBefore('-') }
            return preferred.firstNotNullOfOrNull(::ofCode) ?: ENGLISH
        }
    }
}

/**
 * Interface text from the suite's language files ([format](https://github.com/kumarsumit17/ks-utility/blob/main/specs/translation-format.md)):
 * ks-utility's shared catalogue (`lang/common`), the core's files (`lang/core`) and each plugin's
 * (`lang/<plugin id>`), packed as resources. Later layers are searched first; a key missing in Hindi
 * comes from English; a key missing everywhere is returned as it is.
 */
class Strings(layers: List<String>, loader: ClassLoader = Strings::class.java.classLoader) {
    private val catalogs: Map<Language, List<JsonObject>> = Language.entries.associateWith { lang ->
        layers.reversed().mapNotNull { layer ->
            loader.getResource("lang/$layer/${lang.code}.json")?.readText()?.let { Json.parseToJsonElement(it).jsonObject }
        }
    }

    fun has(key: String) = catalogs.getValue(Language.ENGLISH).any { key in it }

    /** The text of [key] in [lang], with `{name}` placeholders filled from [params]. */
    fun get(lang: Language, key: String, params: Map<String, Any> = emptyMap()): String {
        val entry = lookup(lang, key) ?: lookup(Language.ENGLISH, key) ?: return key
        val text = when (entry) {
            is JsonPrimitive -> entry.content
            is JsonObject -> {
                val count = (params["count"] as? Number)?.toLong() ?: 0
                val form = if (isOne(lang, count)) "one" else "other"
                (entry[form] ?: entry["other"])?.jsonPrimitive?.content ?: key
            }
            else -> key
        }
        return Regex("""\{([A-Za-z][A-Za-z0-9]*)}""").replace(text) { m ->
            params[m.groupValues[1]]?.let { format(lang, it) } ?: m.value
        }
    }

    private fun lookup(lang: Language, key: String) = catalogs.getValue(lang).firstNotNullOfOrNull { it[key] }

    /** CLDR: English `one` is exactly 1; Hindi `one` is 0 and 1. */
    private fun isOne(lang: Language, n: Long) = when (lang) {
        Language.ENGLISH -> n == 1L
        Language.HINDI -> n == 0L || n == 1L
    }

    /** Numbers with Latin digits; English groups by thousands, Hindi the Indian way (1,00,000). */
    private fun format(lang: Language, value: Any): String {
        if (value !is Int && value !is Long) return value.toString()
        val n = (value as Number).toLong()
        val digits = kotlin.math.abs(n).toString()
        val grouped = when (lang) {
            Language.ENGLISH -> digits.reversed().chunked(3).joinToString(",").reversed()
            Language.HINDI -> if (digits.length <= 3) digits else {
                val head = digits.dropLast(3)
                head.reversed().chunked(2).joinToString(",").reversed() + "," + digits.takeLast(3)
            }
        }
        return if (n < 0) "-$grouped" else grouped
    }
}
