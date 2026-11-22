/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Unit and Regression Test Suite for LanguageManager and State Synchronization.
 */

package com.khatawas.util

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class LanguageManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext

    @Before
    fun setUp() {
        LanguageManager.resetCache()
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(fakePrefs)
    }

    @Test
    fun testDefaultLanguageIsEnglish() {
        // When no preference has been saved yet
        val lang = LanguageManager.getLanguage(fakeContext)
        assertEquals("Default language must be 'en' (English)", LanguageManager.LANG_EN, lang)
        assertEquals("LanguageManager flow must emit 'en'", LanguageManager.LANG_EN, LanguageManager.currentLanguageFlow.value)
    }

    @Test
    fun testSetLanguageHindi() {
        // Switch to Hindi
        LanguageManager.setLanguage(fakeContext, LanguageManager.LANG_HI)

        assertEquals("Hindi must be returned by getLanguage()", LanguageManager.LANG_HI, LanguageManager.getLanguage(fakeContext))
        assertEquals("Hindi must be persisted in SharedPreferences", LanguageManager.LANG_HI, fakePrefs.getString(LanguageManager.KEY_APP_LANGUAGE, null))
        assertEquals("StateFlow must emit 'hi'", LanguageManager.LANG_HI, LanguageManager.currentLanguageFlow.value)
        assertEquals("JVM Locale.getDefault() must be Hindi", "hi", Locale.getDefault().language)
    }

    @Test
    fun testSetLanguageKannada() {
        // Switch to Kannada
        LanguageManager.setLanguage(fakeContext, LanguageManager.LANG_KN)

        assertEquals("Kannada must be returned by getLanguage()", LanguageManager.LANG_KN, LanguageManager.getLanguage(fakeContext))
        assertEquals("Kannada must be persisted in SharedPreferences", LanguageManager.LANG_KN, fakePrefs.getString(LanguageManager.KEY_APP_LANGUAGE, null))
        assertEquals("StateFlow must emit 'kn'", LanguageManager.LANG_KN, LanguageManager.currentLanguageFlow.value)
        assertEquals("JVM Locale.getDefault() must be Kannada", "kn", Locale.getDefault().language)
    }

    @Test
    fun testTransitionCycleEnglishToHindiToKannadaToEnglish() {
        // 1. Initial English
        assertEquals(LanguageManager.LANG_EN, LanguageManager.getLanguage(fakeContext))

        // 2. English -> Hindi
        LanguageManager.setLanguage(fakeContext, LanguageManager.LANG_HI)
        assertEquals(LanguageManager.LANG_HI, LanguageManager.getLanguage(fakeContext))
        assertEquals(LanguageManager.LANG_HI, fakePrefs.getString(LanguageManager.KEY_APP_LANGUAGE, null))

        // 3. Hindi -> Kannada
        LanguageManager.setLanguage(fakeContext, LanguageManager.LANG_KN)
        assertEquals(LanguageManager.LANG_KN, LanguageManager.getLanguage(fakeContext))
        assertEquals(LanguageManager.LANG_KN, fakePrefs.getString(LanguageManager.KEY_APP_LANGUAGE, null))

        // 4. Kannada -> English
        LanguageManager.setLanguage(fakeContext, LanguageManager.LANG_EN)
        assertEquals(LanguageManager.LANG_EN, LanguageManager.getLanguage(fakeContext))
        assertEquals(LanguageManager.LANG_EN, fakePrefs.getString(LanguageManager.KEY_APP_LANGUAGE, null))
    }

    @Test
    fun testPersistenceSurvivesProcessRestartSimulation() {
        // Set Kannada
        LanguageManager.setLanguage(fakeContext, LanguageManager.LANG_KN)
        assertEquals(LanguageManager.LANG_KN, LanguageManager.getLanguage(fakeContext))

        // Simulate app kill / in-memory cache clear
        LanguageManager.resetCache()

        // New Context instance accessing same persistent store
        val newContext = FakeContext(fakePrefs)
        val restoredLang = LanguageManager.getLanguage(newContext)

        assertEquals("Language preference must persist across app restarts", LanguageManager.LANG_KN, restoredLang)
        assertEquals(LanguageManager.LANG_KN, LanguageManager.currentLanguageFlow.value)
    }

    @Test
    fun testUnsupportedLanguageFallsBackToEnglish() {
        // If an invalid or unrecognised language is passed (e.g. "fr", "de", "xyz", "")
        LanguageManager.setLanguage(fakeContext, "fr")
        assertEquals("Unsupported language 'fr' must fall back to 'en'", LanguageManager.LANG_EN, LanguageManager.getLanguage(fakeContext))

        LanguageManager.setLanguage(fakeContext, "unknown_code")
        assertEquals("Unsupported language 'unknown_code' must fall back to 'en'", LanguageManager.LANG_EN, LanguageManager.getLanguage(fakeContext))
    }

    @Test
    fun testRapidRepeatedSelectionStability() {
        val sequence = listOf("en", "hi", "kn", "hi", "en", "kn", "hi", "hi", "kn", "en")
        for (code in sequence) {
            LanguageManager.setLanguage(fakeContext, code)
            assertEquals("Language state must remain immediately consistent", code, LanguageManager.getLanguage(fakeContext))
            assertEquals("Preference storage must match target", code, fakePrefs.getString(LanguageManager.KEY_APP_LANGUAGE, null))
            assertEquals("StateFlow must match target", code, LanguageManager.currentLanguageFlow.value)
        }
    }

    @Test
    fun testSupportedLanguagesIntegrity() {
        assertTrue(LanguageManager.SUPPORTED_LANGUAGES.contains("en"))
        assertTrue(LanguageManager.SUPPORTED_LANGUAGES.contains("hi"))
        assertTrue(LanguageManager.SUPPORTED_LANGUAGES.contains("kn"))
        assertEquals(3, LanguageManager.SUPPORTED_LANGUAGES.size)
    }

    // --- Lightweight test doubles ---

    private class FakeContext(private val prefs: FakeSharedPreferences) : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefs
        }
        override fun getApplicationContext(): Context {
            return this
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = data

        override fun getString(key: String?, defValue: String?): String? {
            return (data[key] as? String) ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = FakeEditor(data)

        private class FakeEditor(private val data: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearPending = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else pending.remove(key)
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearPending = true
                return this
            }

            override fun commit(): Boolean {
                if (clearPending) data.clear()
                for ((k, v) in pending) {
                    if (v != null) data[k] = v else data.remove(k)
                }
                pending.clear()
                clearPending = false
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
