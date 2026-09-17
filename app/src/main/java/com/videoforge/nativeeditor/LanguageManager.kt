package com.videoforge.nativeeditor

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

enum class AppLanguage(val tag: String) {
    ARABIC("ar"),
    ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            if (tag == ARABIC.tag) ARABIC else ENGLISH
    }
}

object LanguageManager {
    private const val PREFS = "videoforge_preferences"
    private const val KEY_LANGUAGE = "language"

    fun getLanguage(context: Context): AppLanguage {
        val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        return AppLanguage.fromTag(tag)
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, language.tag).apply()

        Locale.setDefault(Locale(language.tag))
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(android.os.LocaleList(Locale(language.tag)))
        } else {
            @Suppress("DEPRECATION")
            config.locale = Locale(language.tag)
        }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }
}
