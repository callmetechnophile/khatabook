import os

path = 'app/src/main/java/com/khatawas/MainActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Imports
if 'import com.khatawas.util.LanguageManager' not in content:
    content = content.replace(
        'import android.widget.Button\n',
        'import android.widget.Button\nimport android.widget.RadioButton\nimport android.widget.RadioGroup\nimport com.khatawas.util.LanguageManager\n'
    )

# 2. attachBaseContext
if 'override fun attachBaseContext' not in content:
    content = content.replace(
        '    @SuppressLint("ClickableViewAccessibility")\n    override fun onCreate(savedInstanceState: Bundle?) {',
        '    override fun attachBaseContext(newBase: Context) {\n        super.attachBaseContext(LanguageManager.wrapContext(newBase))\n    }\n\n    @SuppressLint("ClickableViewAccessibility")\n    override fun onCreate(savedInstanceState: Bundle?) {'
    )

# 3. LanguageManager.applyToResources and restore initialTab in onCreate
if 'LanguageManager.applyToResources' not in content:
    content = content.replace(
        '        setContentView(R.layout.activity_main)\n',
        '        LanguageManager.applyToResources(resources, this)\n        setContentView(R.layout.activity_main)\n'
    )

if 'val initialTab = savedInstanceState' not in content:
    content = content.replace(
        '        setupSettingsTab()\n',
        '        setupSettingsTab()\n\n        val initialTab = savedInstanceState?.getInt("KEY_ACTIVE_TAB", 0) ?: 0\n        switchToTab(initialTab)\n'
    )

# 4. onSaveInstanceState
if 'override fun onSaveInstanceState' not in content:
    content = content.replace(
        '    override fun onPause() {',
        '    override fun onSaveInstanceState(outState: Bundle) {\n        super.onSaveInstanceState(outState)\n        outState.putInt("KEY_ACTIVE_TAB", currentTab)\n    }\n\n    override fun onPause() {'
    )

# 5. currentTab variable and tracking
if 'private var currentTab: Int = 0' not in content:
    content = content.replace(
        '    // Tab 5: Settings Views\n',
        '    private var currentTab: Int = 0\n\n    // Tab 5: Settings Views\n'
    )

if 'currentTab = tabIndex' not in content:
    content = content.replace(
        '    private fun switchToTab(tabIndex: Int) {\n',
        '    private fun switchToTab(tabIndex: Int) {\n        currentTab = tabIndex\n'
    )

# 6. Settings view declarations
old_decl = '''    // Tab 5: Settings Views
    private lateinit var btnLangEn: Button
    private lateinit var btnLangHi: Button
    private lateinit var btnLangKn: Button'''

new_decl = '''    // Tab 5: Settings Views
    private var rgLanguageSelector: RadioGroup? = null
    private var rbLangEn: RadioButton? = null
    private var rbLangHi: RadioButton? = null
    private var rbLangKn: RadioButton? = null
    private lateinit var btnLangEn: Button
    private lateinit var btnLangHi: Button
    private lateinit var btnLangKn: Button'''

if old_decl in content:
    content = content.replace(old_decl, new_decl)

# 7. setupSettingsTab and updateLanguageSelectionUi and changeLanguage
# Normalize newlines if CRLF
old_setup_pattern = "    private fun setupSettingsTab() {"
setup_idx = content.find(old_setup_pattern)
if setup_idx != -1:
    end_of_change_lang = content.find("    /**\n     * Fast 1-tap manual transaction dialog", setup_idx)
    if end_of_change_lang == -1:
        end_of_change_lang = content.find("    /**\r\n     * Fast 1-tap manual transaction dialog", setup_idx)
    
    if end_of_change_lang != -1:
        new_methods = '''    private fun setupSettingsTab() {
        rgLanguageSelector = findViewById(R.id.rg_language_selector)
        rbLangEn = findViewById(R.id.rb_lang_en)
        rbLangHi = findViewById(R.id.rb_lang_hi)
        rbLangKn = findViewById(R.id.rb_lang_kn)
        btnLangEn = findViewById(R.id.btn_lang_en)
        btnLangHi = findViewById(R.id.btn_lang_hi)
        btnLangKn = findViewById(R.id.btn_lang_kn)

        val currentLang = LanguageManager.getLanguage(this)
        updateLanguageSelectionUi(currentLang)

        rgLanguageSelector?.setOnCheckedChangeListener { _, checkedId ->
            val targetLang = when (checkedId) {
                R.id.rb_lang_hi -> LanguageManager.LANG_HI
                R.id.rb_lang_kn -> LanguageManager.LANG_KN
                else -> LanguageManager.LANG_EN
            }
            if (targetLang != LanguageManager.getLanguage(this)) {
                changeLanguage(targetLang)
            }
        }

        btnLangEn.setOnClickListener { changeLanguage(LanguageManager.LANG_EN) }
        btnLangHi.setOnClickListener { changeLanguage(LanguageManager.LANG_HI) }
        btnLangKn.setOnClickListener { changeLanguage(LanguageManager.LANG_KN) }
    }

    private fun updateLanguageSelectionUi(langCode: String) {
        rgLanguageSelector?.setOnCheckedChangeListener(null)
        when (langCode) {
            LanguageManager.LANG_HI -> {
                rbLangHi?.isChecked = true
                rbLangEn?.isChecked = false
                rbLangKn?.isChecked = false
            }
            LanguageManager.LANG_KN -> {
                rbLangKn?.isChecked = true
                rbLangEn?.isChecked = false
                rbLangHi?.isChecked = false
            }
            else -> {
                rbLangEn?.isChecked = true
                rbLangHi?.isChecked = false
                rbLangKn?.isChecked = false
            }
        }
        rgLanguageSelector?.setOnCheckedChangeListener { _, checkedId ->
            val targetLang = when (checkedId) {
                R.id.rb_lang_hi -> LanguageManager.LANG_HI
                R.id.rb_lang_kn -> LanguageManager.LANG_KN
                else -> LanguageManager.LANG_EN
            }
            if (targetLang != LanguageManager.getLanguage(this)) {
                changeLanguage(targetLang)
            }
        }

        val activeBg = ContextCompat.getColor(this, R.color.brand_primary)
        val activeText = ContextCompat.getColor(this, R.color.white)
        val inactiveText = ContextCompat.getColor(this, R.color.text_primary)

        val isEn = langCode == LanguageManager.LANG_EN
        val isHi = langCode == LanguageManager.LANG_HI
        val isKn = langCode == LanguageManager.LANG_KN

        btnLangEn.setBackgroundColor(if (isEn) activeBg else Color.TRANSPARENT)
        btnLangEn.setTextColor(if (isEn) activeText else inactiveText)

        btnLangHi.setBackgroundColor(if (isHi) activeBg else Color.TRANSPARENT)
        btnLangHi.setTextColor(if (isHi) activeText else inactiveText)

        btnLangKn.setBackgroundColor(if (isKn) activeBg else Color.TRANSPARENT)
        btnLangKn.setTextColor(if (isKn) activeText else inactiveText)
    }

    private fun changeLanguage(langCode: String) {
        val current = LanguageManager.getLanguage(this)
        if (current == langCode) return
        LanguageManager.setLanguage(this, langCode)
        updateLanguageSelectionUi(langCode)
        recreate()
    }

'''
        content = content[:setup_idx] + new_methods + content[end_of_change_lang:]

# 8. onResume update
if 'updateLanguageSelectionUi' not in content:
    content = content.replace(
        '    override fun onResume() {\n        super.onResume()\n        updatePrivacyUi()\n',
        '    override fun onResume() {\n        super.onResume()\n        updatePrivacyUi()\n        updateLanguageSelectionUi(LanguageManager.getLanguage(this))\n'
    )

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)

print("SUCCESS: MainActivity.kt updated.")
