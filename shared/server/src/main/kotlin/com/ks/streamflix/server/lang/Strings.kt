package com.ks.streamflix.server.lang

import com.ks.utility.lang.Catalog
import com.ks.utility.lang.Translator

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
 * Interface text from the suite's language files, looked up with ks-utility's [Translator]:
 * ks-utility's shared catalogue (`lang/common`), the API's texts (`lang/core`) and each plugin's
 * (`lang/<plugin id>`), packed as resources. Later layers are searched first; a key missing in Hindi
 * comes from English; a key missing everywhere is returned as it is.
 */
class Strings(layers: List<String>, loader: ClassLoader = Strings::class.java.classLoader) {
    private val catalogs: List<Map<String, Catalog>> = layers.map { layer ->
        Language.entries.mapNotNull { lang ->
            loader.getResource("lang/$layer/${lang.code}.json")?.readText()?.let { lang.code to Catalog.parse(it) }
        }.toMap()
    }
    private val translators = Language.entries.associateWith { Translator(it.code, catalogs) }

    fun has(key: String) = translators.getValue(Language.ENGLISH).has(key)

    /** The text of [key] in [lang], with `{name}` placeholders filled from [params]. */
    fun get(lang: Language, key: String, params: Map<String, Any> = emptyMap()): String = translators.getValue(lang).t(key, params)
}
