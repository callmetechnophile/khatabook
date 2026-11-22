/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Authoritative Single Source of Truth for Application Language State and Locale Synchronization.
 */
package com.khatawas.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Authoritative single source of truth for language management across KHATAWAS.
 *
 * Guarantees that:
 * 1. The selected language is persistently saved.
 * 2. It is never overridden by Android system locale unless uninitialized.
 * 3. The UI state indicator remains synchronized with the active locale.
 * 4. State updates are observable reactively.
 */
object LanguageManager {
    const val PREFS_NAME = "khatawas_app_prefs"
    const val KEY_APP_LANGUAGE = "app_selected_language"

    const val LANG_EN = "en"
    const val LANG_HI = "hi"
    const val LANG_KN = "kn"

    val SUPPORTED_LANGUAGES = listOf(LANG_EN, LANG_HI, LANG_KN)

    private val _currentLanguageFlow = MutableStateFlow(LANG_EN)
    val currentLanguageFlow: StateFlow<String> = _currentLanguageFlow.asStateFlow()

    @Volatile
    private var cachedLanguage: String? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves the authoritative application language code.
     * Returns "en", "hi", or "kn".
     */
    fun getLanguage(context: Context): String {
        cachedLanguage?.let { return it }
        val saved = getPrefs(context).getString(KEY_APP_LANGUAGE, null)
        val valid = if (saved != null && saved in SUPPORTED_LANGUAGES) saved else LANG_EN
        cachedLanguage = valid
        _currentLanguageFlow.value = valid
        return valid
    }

    /**
     * Persistently saves the selected language and updates the active locale state.
     */
    fun setLanguage(context: Context, langCode: String): Context {
        val valid = if (langCode in SUPPORTED_LANGUAGES) langCode else LANG_EN
        getPrefs(context).edit().putString(KEY_APP_LANGUAGE, valid).commit()
        cachedLanguage = valid
        _currentLanguageFlow.value = valid

        val locale = Locale(valid)
        Locale.setDefault(locale)
        return wrapContext(context)
    }

    /**
     * Wraps the context configuration with the persisted application locale.
     */
    fun wrapContext(context: Context): Context {
        val langCode = getLanguage(context)
        val locale = Locale(langCode)
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            return context
        }
    }

    /**
     * Applies the authoritative locale configuration directly to the given resources.
     */
    fun applyToResources(resources: Resources, context: Context) {
        val langCode = getLanguage(context)
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /**
     * Clears cached state (used for testing resets).
     */
    fun resetCache() {
        cachedLanguage = null
        _currentLanguageFlow.value = LANG_EN
    }
}
