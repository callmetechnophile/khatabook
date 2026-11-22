/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Rural-First Android Mobile Application for Kirana and village merchants.
 * Supports 5 shallow destinations: Home, Customers, History, Assistant, Settings.
 * Provides 1-tap manual credit/debit, prominent voice entry, customer directory with family separation,
 * high-contrast typography, SQLCipher local encrypted storage, and offline-first manual sync.
 */

package com.khatawas

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import com.khatawas.util.LanguageManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.khatawas.audio.AudioPipeline
import com.khatawas.audio.GnaniSttClient
import com.khatawas.audio.SttListener
import com.khatawas.audio.SttState
import com.khatawas.auth.BiometricAuthHelper
import com.khatawas.auth.PrivacyManager
import com.khatawas.core.AppOrchestrator
import com.khatawas.logic.LedgerParser
import com.khatawas.logic.ParseResult
import com.khatawas.logic.Transaction
import com.khatawas.sync.SyncManager
import com.khatawas.sync.SyncState
import com.khatawas.ui.CustomerAdapter
import com.khatawas.ui.CustomerUiModel
import com.khatawas.ui.IndianCurrencyFormatter
import com.khatawas.ui.TransactionAdapter
import com.khatawas.ui.TransactionCategory
import com.khatawas.ui.TransactionUiModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        private const val REQUEST_RECORD_AUDIO = 101
        private const val PREFS_NAME = "khatawas_app_prefs"
        private const val KEY_AI_DISCLOSURE_SHOWN = "has_shown_ai_disclosure"
        private const val KEY_APP_LANGUAGE = "app_selected_language"
    }

    private lateinit var appOrchestrator: AppOrchestrator
    private lateinit var audioPipeline: AudioPipeline
    private lateinit var syncManager: SyncManager
    private var gnaniSttClient: GnaniSttClient? = null

    @Volatile
    private var sessionActive = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var releaseTimeoutRunnable: Runnable? = null

    // Top Bar & Global Controls
    private lateinit var btnPushToTalk: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvConnection: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvSyncSubtitle: TextView
    private lateinit var btnCloudSync: ImageButton
    private lateinit var pbSyncProgress: ProgressBar
    private lateinit var btnOpenChatbot: Button
    private lateinit var tvTranscript: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvResultDetail: TextView
    private lateinit var switchRnnoise: SwitchCompat
    private lateinit var btnAiDisclosure: Button
    private lateinit var btnPurgeData: Button

    // Overview & Customer Ledger
    private lateinit var tvLedgerHeader: TextView
    private lateinit var btnFilterReset: Button
    private lateinit var tvLedgerCustomerName: TextView
    private lateinit var tvLedgerBalance: TextView
    private lateinit var tvLedgerBalanceSubtitle: TextView
    private lateinit var llEmptyState: LinearLayout
    private lateinit var rvTransactions: RecyclerView
    private lateinit var transactionAdapter: TransactionAdapter

    // Privacy and Authentication Views
    private lateinit var llPrivacyBanner: LinearLayout
    private lateinit var tvPrivacyStatus: TextView
    private lateinit var tvPrivacySubtitle: TextView
    private lateinit var btnAuthAction: Button
    private lateinit var btnPrivacyConfig: Button

    // Rural-First 5 Bottom Navigation Containers
    private lateinit var containerTabHome: LinearLayout
    private lateinit var containerTabCustomers: LinearLayout
    private lateinit var containerTabHistory: LinearLayout
    private lateinit var containerTabAssistant: LinearLayout
    private lateinit var containerTabSettings: LinearLayout

    // Bottom Navigation Bar Items
    private lateinit var navTabHome: LinearLayout
    private lateinit var navTabCustomers: LinearLayout
    private lateinit var navTabHistory: LinearLayout
    private lateinit var navTabAssistant: LinearLayout
    private lateinit var navTabSettings: LinearLayout
    private lateinit var ivNavHome: ImageView
    private lateinit var tvNavHome: TextView
    private lateinit var ivNavCustomers: ImageView
    private lateinit var tvNavCustomers: TextView
    private lateinit var ivNavHistory: ImageView
    private lateinit var tvNavHistory: TextView
    private lateinit var ivNavAssistant: ImageView
    private lateinit var tvNavAssistant: TextView
    private lateinit var ivNavSettings: ImageView
    private lateinit var tvNavSettings: TextView

    // Tab 1: Home Primary Actions
    private lateinit var btnHomeGiveCredit: Button
    private lateinit var btnHomeReceivePayment: Button
    private lateinit var btnHomeViewAllHistory: TextView

    // Tab 2: Customer Directory Views
    private lateinit var etCustomerSearch: EditText
    private lateinit var btnAddCustomerScreen: Button
    private lateinit var rvCustomerList: RecyclerView
    private lateinit var customerAdapter: CustomerAdapter
    private var fullCustomerList: List<CustomerUiModel> = emptyList()

    // Tab 3: History Tab Filter Chips & List
    private lateinit var chipFilterAll: Button
    private lateinit var chipFilterCredit: Button
    private lateinit var chipFilterDebit: Button
    private lateinit var chipFilterStuck: Button
    private lateinit var chipFilterIllegal: Button
    private lateinit var rvHistoryTabTransactions: RecyclerView
    private lateinit var historyTransactionAdapter: TransactionAdapter
    private var currentCategoryFilter: String? = null

    private var currentTab: Int = 0

    // Tab 5: Settings Views
    private var rgLanguageSelector: RadioGroup? = null
    private var rbLangEn: RadioButton? = null
    private var rbLangHi: RadioButton? = null
    private var rbLangKn: RadioButton? = null
    private lateinit var btnLangEn: Button
    private lateinit var btnLangHi: Button
    private lateinit var btnLangKn: Button

    private var lastInteractionTimestamp: Long = System.currentTimeMillis()
    private var cachedLatestAmountString: String = "No ledger entry saved yet."
    private var cachedLatestDetailString: String = "A confirmed entry will be displayed here."
    private var cachedBalancePaise: Long = 0L

    private val inactivityCheckRunnable = object : Runnable {
        override fun run() {
            val timeout = PrivacyManager.getInactivityTimeoutMs(this@MainActivity)
            if (PrivacyManager.isUnlocked && (System.currentTimeMillis() - lastInteractionTimestamp >= timeout)) {
                PrivacyManager.lockSession()
                updatePrivacyUi()
                refreshLedgerAndTransactions(currentFilteredCustomer)
                Toast.makeText(this@MainActivity, "Session locked due to inactivity", Toast.LENGTH_SHORT).show()
            }
            mainHandler.postDelayed(this, 5000L)
        }
    }

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var currentFilteredCustomer: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrapContext(newBase))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Mandatory locked state on application startup
        PrivacyManager.lockSession()

        LanguageManager.applyToResources(resources, this)
        setContentView(R.layout.activity_main)

        // Initialize UI Components
        initViews()
        setupBottomNavigation()
        setupPrimaryActions()
        setupCustomerDirectory()
        setupHistoryFilters()
        setupSettingsTab()

        val initialTab = savedInstanceState?.getInt("KEY_ACTIVE_TAB", 0) ?: 0
        switchToTab(initialTab)

        // Phase 5 Runtime JNI Verification for Milestones 2-5
        runMilestone2Verification()
        runMilestone3Verification()
        runMilestone4Verification()
        runMilestone5Verification()

        appOrchestrator = AppOrchestrator(this)
        audioPipeline = AudioPipeline(appOrchestrator.rnnoiseProcessor)
        syncManager = SyncManager(this, appOrchestrator.ledgerDatabase)

        syncManager.onSyncStatusChanged = { status ->
            runOnUiThread {
                tvSyncStatus.text = status
            }
        }
        syncManager.onSyncStateChanged = { state ->
            runOnUiThread {
                updateSyncUi(state)
            }
        }
        updateSyncUi(syncManager.getCurrentSyncState())

        audioPipeline.onPcm16kReady = { chunk ->
            gnaniSttClient?.sendAudioChunk(chunk)
        }

        appOrchestrator.onTransactionSaved = { transaction ->
            val isCredit = transaction.type == "CREDIT"
            val direction = if (isCredit) "Give Udhaar (+)" else if (transaction.type == "PAYMENT") "Got Payment (-)" else "Reversal processed"
            cachedLatestAmountString = "${transaction.name}  ·  ${IndianCurrencyFormatter.formatPaise(transaction.amountPaise)}"
            cachedLatestDetailString = "$direction  •  Offset: ${transaction.dateOffset} day(s)"
            tvResult.setTextColor(ContextCompat.getColor(this, if (isCredit) R.color.btn_credit_bg else R.color.btn_debit_bg))
            tvStatus.text = "Saved to your local encrypted ledger."
            updateSyncUi(syncManager.getCurrentSyncState())
            refreshLedgerAndTransactions(null)
        }

        appOrchestrator.onBatchTransactionsSaved = { transactions, netBalancePaise ->
            val count = transactions.size
            cachedLatestAmountString = "${transactions.first().name}  ·  $count entries"
            cachedLatestDetailString = "Net outstanding balance: ${IndianCurrencyFormatter.formatPaise(netBalancePaise)}"
            tvStatus.text = "Multiple transactions saved to local ledger."
            updateSyncUi(syncManager.getCurrentSyncState())
            refreshLedgerAndTransactions(null)
        }

        appOrchestrator.onQueryResult = { customer, balancePaise ->
            cachedLatestAmountString = "$customer  ·  Balance"
            cachedLatestDetailString = "Net outstanding: ${IndianCurrencyFormatter.formatPaise(balancePaise)}"
            tvStatus.text = "Ledger query result."
            refreshLedgerAndTransactions(customer)
        }

        appOrchestrator.onTransactionRejected = { reason ->
            tvStatus.text = reason
            tvResult.text = "Action required"
            tvResultDetail.text = "Review the spoken sentence or respond to clarification."
        }

        switchRnnoise.setOnCheckedChangeListener { _, isChecked ->
            audioPipeline.isRnnoiseEnabled = isChecked
            switchRnnoise.text = if (isChecked) "Filter On" else "Filter Off"
        }

        btnAiDisclosure.setOnClickListener {
            showAiDisclosureDialog(spoken = false)
        }

        btnPurgeData.setOnClickListener {
            showPurgeDataConfirmation()
        }

        checkFirstLaunchDisclosure()
        refreshLedgerAndTransactions(null)

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            btnPushToTalk.isEnabled = true
        } else {
            btnPushToTalk.isEnabled = false
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }

        btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!btnPushToTalk.isEnabled || sessionActive) return@setOnTouchListener true
                    btnPushToTalk.text = "CONNECTING…"
                    btnPushToTalk.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_mic_recording))
                    tvStatus.text = "Opening speech session. Keep holding the button."
                    tvTranscript.text = "Waiting for speech…"
                    tvConnection.text = "●  Connecting to speech service…"
                    startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btnPushToTalk.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_mic_idle))
                    stopListening()
                    true
                }
                else -> false
            }
        }
    }

    private fun initViews() {
        llPrivacyBanner = findViewById(R.id.ll_privacy_banner)
        tvPrivacyStatus = findViewById(R.id.tv_privacy_status)
        tvPrivacySubtitle = findViewById(R.id.tv_privacy_subtitle)
        btnAuthAction = findViewById(R.id.btn_auth_action)
        btnPrivacyConfig = findViewById(R.id.btn_privacy_config)

        btnAuthAction.setOnClickListener {
            handleAuthAction()
        }

        btnPrivacyConfig.setOnClickListener {
            showPrivacyConfigDialog()
        }

        btnPushToTalk = findViewById(R.id.btn_push_to_talk)
        tvStatus = findViewById(R.id.tv_status)
        tvConnection = findViewById(R.id.tv_connection)
        tvSyncStatus = findViewById(R.id.tv_sync_status)
        tvSyncSubtitle = findViewById(R.id.tv_sync_subtitle)
        btnCloudSync = findViewById(R.id.btn_cloud_sync)
        pbSyncProgress = findViewById(R.id.pb_sync_progress)
        btnOpenChatbot = findViewById(R.id.btn_open_chatbot)
        btnOpenChatbot.setOnClickListener {
            startActivity(android.content.Intent(this, com.khatawas.ui.ChatbotActivity::class.java))
        }
        tvTranscript = findViewById(R.id.tv_transcript)
        tvResult = findViewById(R.id.tv_result)
        tvResultDetail = findViewById(R.id.tv_result_detail)
        switchRnnoise = findViewById(R.id.switch_rnnoise)
        btnAiDisclosure = findViewById(R.id.btn_ai_disclosure)
        btnPurgeData = findViewById(R.id.btn_purge_data)

        btnCloudSync.setOnClickListener {
            triggerCloudBackup()
        }

        // Overview & Transactions
        tvLedgerHeader = findViewById(R.id.tv_ledger_header)
        btnFilterReset = findViewById(R.id.btn_filter_reset)
        tvLedgerCustomerName = findViewById(R.id.tv_ledger_customer_name)
        tvLedgerBalance = findViewById(R.id.tv_ledger_balance)
        tvLedgerBalanceSubtitle = findViewById(R.id.tv_ledger_balance_subtitle)
        llEmptyState = findViewById(R.id.ll_empty_state)
        rvTransactions = findViewById(R.id.rv_transactions)

        transactionAdapter = TransactionAdapter(
            isUnlocked = PrivacyManager.isUnlocked,
            maskCustomerNames = PrivacyManager.isMaskCustomerNamesEnabled(this)
        ) { item ->
            refreshLedgerAndTransactions(item.customerName)
            switchToTab(2) // Switch to History
        }
        rvTransactions.layoutManager = LinearLayoutManager(this)
        rvTransactions.adapter = transactionAdapter

        btnFilterReset.setOnClickListener {
            refreshLedgerAndTransactions(null)
        }

        // 5 Destination Containers
        containerTabHome = findViewById(R.id.container_tab_home)
        containerTabCustomers = findViewById(R.id.container_tab_customers)
        containerTabHistory = findViewById(R.id.container_tab_history)
        containerTabAssistant = findViewById(R.id.container_tab_assistant)
        containerTabSettings = findViewById(R.id.container_tab_settings)

        // Bottom Navigation Items
        navTabHome = findViewById(R.id.nav_tab_home)
        navTabCustomers = findViewById(R.id.nav_tab_customers)
        navTabHistory = findViewById(R.id.nav_tab_history)
        navTabAssistant = findViewById(R.id.nav_tab_assistant)
        navTabSettings = findViewById(R.id.nav_tab_settings)

        ivNavHome = findViewById(R.id.iv_nav_home)
        tvNavHome = findViewById(R.id.tv_nav_home)
        ivNavCustomers = findViewById(R.id.iv_nav_customers)
        tvNavCustomers = findViewById(R.id.tv_nav_customers)
        ivNavHistory = findViewById(R.id.iv_nav_history)
        tvNavHistory = findViewById(R.id.tv_nav_history)
        ivNavAssistant = findViewById(R.id.iv_nav_assistant)
        tvNavAssistant = findViewById(R.id.tv_nav_assistant)
        ivNavSettings = findViewById(R.id.iv_nav_settings)
        tvNavSettings = findViewById(R.id.tv_nav_settings)
    }

    private fun setupBottomNavigation() {
        navTabHome.setOnClickListener { switchToTab(0) }
        navTabCustomers.setOnClickListener { switchToTab(1) }
        navTabHistory.setOnClickListener { switchToTab(2) }
        navTabAssistant.setOnClickListener { switchToTab(3) }
        navTabSettings.setOnClickListener { switchToTab(4) }
    }

    private fun switchToTab(tabIndex: Int) {
        currentTab = tabIndex
        val activeColor = ContextCompat.getColor(this, R.color.primary_brand)
        val inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive_item)

        containerTabHome.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        containerTabCustomers.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        containerTabHistory.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        containerTabAssistant.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
        containerTabSettings.visibility = if (tabIndex == 4) View.VISIBLE else View.GONE

        // Update Tab visual indicators
        ivNavHome.setColorFilter(if (tabIndex == 0) activeColor else inactiveColor)
        tvNavHome.setTextColor(if (tabIndex == 0) activeColor else inactiveColor)

        ivNavCustomers.setColorFilter(if (tabIndex == 1) activeColor else inactiveColor)
        tvNavCustomers.setTextColor(if (tabIndex == 1) activeColor else inactiveColor)

        ivNavHistory.setColorFilter(if (tabIndex == 2) activeColor else inactiveColor)
        tvNavHistory.setTextColor(if (tabIndex == 2) activeColor else inactiveColor)

        ivNavAssistant.setColorFilter(if (tabIndex == 3) activeColor else inactiveColor)
        tvNavAssistant.setTextColor(if (tabIndex == 3) activeColor else inactiveColor)

        ivNavSettings.setColorFilter(if (tabIndex == 4) activeColor else inactiveColor)
        tvNavSettings.setTextColor(if (tabIndex == 4) activeColor else inactiveColor)

        if (tabIndex == 1) {
            loadCustomersDirectory()
        } else if (tabIndex == 2) {
            loadHistoryTab()
        }
    }

    private fun setupPrimaryActions() {
        btnHomeGiveCredit = findViewById(R.id.btn_home_give_credit)
        btnHomeReceivePayment = findViewById(R.id.btn_home_receive_payment)
        btnHomeViewAllHistory = findViewById(R.id.btn_home_view_all_history)

        btnHomeGiveCredit.setOnClickListener {
            showFastTransactionDialog("CREDIT", null)
        }

        btnHomeReceivePayment.setOnClickListener {
            showFastTransactionDialog("PAYMENT", null)
        }

        btnHomeViewAllHistory.setOnClickListener {
            switchToTab(2)
        }
    }

    private fun setupCustomerDirectory() {
        etCustomerSearch = findViewById(R.id.et_customer_search)
        btnAddCustomerScreen = findViewById(R.id.btn_add_customer_screen)
        rvCustomerList = findViewById(R.id.rv_customer_list)

        customerAdapter = CustomerAdapter(
            isUnlocked = PrivacyManager.isUnlocked,
            maskCustomerNames = PrivacyManager.isMaskCustomerNamesEnabled(this),
            onCustomerClick = { customer ->
                refreshLedgerAndTransactions(customer.name)
                switchToTab(2)
            },
            onGiveCreditClick = { customer ->
                showFastTransactionDialog("CREDIT", customer.name)
            },
            onReceivePaymentClick = { customer ->
                showFastTransactionDialog("PAYMENT", customer.name)
            }
        )
        rvCustomerList.layoutManager = LinearLayoutManager(this)
        rvCustomerList.adapter = customerAdapter

        etCustomerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterCustomerList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnAddCustomerScreen.setOnClickListener {
            showFastTransactionDialog("CREDIT", null)
        }
    }

    private fun setupHistoryFilters() {
        chipFilterAll = findViewById(R.id.chip_filter_all)
        chipFilterCredit = findViewById(R.id.chip_filter_credit)
        chipFilterDebit = findViewById(R.id.chip_filter_debit)
        chipFilterStuck = findViewById(R.id.chip_filter_stuck)
        chipFilterIllegal = findViewById(R.id.chip_filter_illegal)
        rvHistoryTabTransactions = findViewById(R.id.rv_history_tab_transactions)

        historyTransactionAdapter = TransactionAdapter(
            isUnlocked = PrivacyManager.isUnlocked,
            maskCustomerNames = PrivacyManager.isMaskCustomerNamesEnabled(this)
        ) { item ->
            refreshLedgerAndTransactions(item.customerName)
        }
        rvHistoryTabTransactions.layoutManager = LinearLayoutManager(this)
        rvHistoryTabTransactions.adapter = historyTransactionAdapter

        chipFilterAll.setOnClickListener { applyCategoryFilter(null) }
        chipFilterCredit.setOnClickListener { applyCategoryFilter("CREDIT") }
        chipFilterDebit.setOnClickListener { applyCategoryFilter("PAYMENT") }
        chipFilterStuck.setOnClickListener { applyCategoryFilter("STUCK") }
        chipFilterIllegal.setOnClickListener { applyCategoryFilter("ILLEGAL") }
    }

    private fun setupSettingsTab() {
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

    /**
     * Fast 1-tap manual transaction dialog with large inputs and quick rupee increment chips.
     */
    private fun showFastTransactionDialog(type: String, prefilledCustomer: String?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_fast_transaction, null)
        val tvTitle: TextView = dialogView.findViewById(R.id.tv_dialog_title)
        val tvSubtitle: TextView = dialogView.findViewById(R.id.tv_dialog_subtitle)
        val actvCustomer: AutoCompleteTextView = dialogView.findViewById(R.id.actv_dialog_customer)
        val etAmount: EditText = dialogView.findViewById(R.id.et_dialog_amount)
        val etNote: EditText = dialogView.findViewById(R.id.et_dialog_note)
        val btnSave: Button = dialogView.findViewById(R.id.btn_dialog_save)
        val btnCancel: Button = dialogView.findViewById(R.id.btn_dialog_cancel)

        val chip100: Button = dialogView.findViewById(R.id.chip_add_100)
        val chip200: Button = dialogView.findViewById(R.id.chip_add_200)
        val chip500: Button = dialogView.findViewById(R.id.chip_add_500)
        val chip1000: Button = dialogView.findViewById(R.id.chip_add_1000)

        val isCredit = type == "CREDIT"
        tvTitle.text = if (isCredit) "GIVE UDHAAR (+)" else "RECEIVE PAYMENT (-)"
        tvTitle.setTextColor(ContextCompat.getColor(this, if (isCredit) R.color.btn_credit_bg else R.color.btn_debit_bg))
        tvSubtitle.text = if (isCredit) "Add money to customer's outstanding balance" else "Deduct money from customer's outstanding balance"
        btnSave.setBackgroundColor(ContextCompat.getColor(this, if (isCredit) R.color.btn_credit_bg else R.color.btn_debit_bg))
        btnSave.text = if (isCredit) "SAVE UDHAAR ENTRY" else "SAVE PAYMENT ENTRY"

        // Autocomplete suggestions
        bgExecutor.execute {
            val names = appOrchestrator.ledgerDatabase.getAllCustomerNames()
            runOnUiThread {
                val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
                actvCustomer.setAdapter(adapter)
            }
        }

        if (!prefilledCustomer.isNullOrBlank()) {
            actvCustomer.setText(prefilledCustomer)
        }

        // Quick amount increment chips
        fun addAmount(delta: Long) {
            val current = etAmount.text.toString().toLongOrNull() ?: 0L
            etAmount.setText((current + delta).toString())
            etAmount.setSelection(etAmount.text.length)
        }
        chip100.setOnClickListener { addAmount(100) }
        chip200.setOnClickListener { addAmount(200) }
        chip500.setOnClickListener { addAmount(500) }
        chip1000.setOnClickListener { addAmount(1000) }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val customerName = actvCustomer.text.toString().trim()
            val amountRupees = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            val note = etNote.text.toString().trim()

            if (customerName.length < 2) {
                Toast.makeText(this, "Please enter a valid customer name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (amountRupees <= 0.0) {
                Toast.makeText(this, "Please enter a valid amount greater than zero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val amountPaise = Math.round(amountRupees * 100.0)
            val transaction = Transaction(
                name = customerName,
                amountPaise = amountPaise,
                type = type,
                note = note
            )

            bgExecutor.execute {
                val rowId = appOrchestrator.ledgerDatabase.insertTransaction(transaction)
                runOnUiThread {
                    if (rowId > 0) {
                        dialog.dismiss()
                        val feedback = if (isCredit) "$customerName ko ₹${amountRupees.toInt()} udhaar likh diya" else "$customerName se ₹${amountRupees.toInt()} jama kar liye"
                        appOrchestrator.voiceFeedback.speak(feedback)
                        Toast.makeText(this, "Saved: $customerName · ₹${amountRupees.toInt()}", Toast.LENGTH_SHORT).show()
                        refreshLedgerAndTransactions(null)
                        loadCustomersDirectory()
                        loadHistoryTab()
                    } else {
                        Toast.makeText(this, "Failed to save transaction", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun loadCustomersDirectory() {
        bgExecutor.execute {
            val db = appOrchestrator.ledgerDatabase
            val names = db.getAllCustomerNames()
            val models = names.map { name ->
                val balancePaise = db.getCustomerBalancePaise(name)
                val isFamily = name.contains("ke papa", ignoreCase = true) ||
                        name.contains("ki mummy", ignoreCase = true) ||
                        name.contains("ke bhai", ignoreCase = true) ||
                        name.contains("father", ignoreCase = true)
                CustomerUiModel(
                    name = name,
                    balancePaise = balancePaise,
                    isFamilyRelation = isFamily,
                    relationNote = if (isFamily) "Family relation" else ""
                )
            }.sortedByDescending { it.balancePaise }

            fullCustomerList = models
            runOnUiThread {
                customerAdapter.submitList(models)
            }
        }
    }

    private fun filterCustomerList(query: String) {
        if (query.isBlank()) {
            customerAdapter.submitList(fullCustomerList)
        } else {
            val filtered = fullCustomerList.filter {
                it.name.contains(query, ignoreCase = true)
            }
            customerAdapter.submitList(filtered)
        }
    }

    private fun loadHistoryTab() {
        applyCategoryFilter(currentCategoryFilter)
    }

    private fun applyCategoryFilter(category: String?) {
        currentCategoryFilter = category
        bgExecutor.execute {
            val db = appOrchestrator.ledgerDatabase
            val entities = if (category.isNullOrBlank()) {
                if (currentFilteredCustomer.isNullOrBlank()) db.getRecentTransactions(100)
                else db.getTransactionsForCustomer(currentFilteredCustomer!!, 100)
            } else {
                db.getTransactionsByCategory(category, 100)
            }

            val uiModels = entities.map { entity ->
                TransactionUiModel(
                    id = entity.id,
                    eventId = entity.eventId,
                    customerName = entity.customerName,
                    amountPaise = entity.amountPaise,
                    category = TransactionCategory.fromRecord(entity.type, entity.note),
                    timestamp = entity.timestamp,
                    note = entity.note,
                    paymentMethod = if (entity.note.contains("UPI", ignoreCase = true)) "UPI / Bank" else "Cash / Khata",
                    reviewStatus = "Recorded"
                )
            }

            runOnUiThread {
                historyTransactionAdapter.submitList(uiModels)
            }
        }
    }

    private fun checkFirstLaunchDisclosure() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shown = prefs.getBoolean(KEY_AI_DISCLOSURE_SHOWN, false)
        if (!shown) {
            showAiDisclosureDialog(spoken = true)
            prefs.edit().putBoolean(KEY_AI_DISCLOSURE_SHOWN, true).apply()
        }
    }

    private fun showAiDisclosureDialog(spoken: Boolean) {
        val message = """
            KHATAWAS uses speech recognition to convert your spoken transactions into digital ledger entries.
            
            • Your financial ledger is stored locally in an encrypted database (SQLCipher AES-256).
            • Your voice data is used exclusively to transcribe transaction amounts and customer names.
            • We do not sell your data, use your transcripts for advertising, or share data for lending assessments.
            
            खातावास आपकी आवाज से बही-खाता लिखने के लिए स्पीच रिकग्निशन का उपयोग करता है। आपका डेटा स्थानीय रूप से एन्क्रिप्टेड है।
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("AI & Privacy Disclosure")
            .setMessage(message)
            .setPositiveButton("I Understand") { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        if (spoken) {
            appOrchestrator.voiceFeedback.speak("खातावास में आपका स्वागत है। बोलकर बही-खाता लिखने के लिए बटन दबाकर रखें।")
        }
    }

    private fun showPurgeDataConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear All Ledger Data?")
            .setMessage("This will permanently delete all customer names, transactions, and sync records from your local encrypted ledger. This action cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear Data") { _, _ ->
                val success = appOrchestrator.ledgerDatabase.purgeAllData()
                if (success) {
                    cachedLatestAmountString = "All data cleared"
                    cachedLatestDetailString = "Ledger and customer database has been reset."
                    cachedBalancePaise = 0L
                    tvStatus.text = "Local database purged successfully."
                    tvTranscript.text = "Nothing recognized yet."
                    updateSyncUi(syncManager.getCurrentSyncState())
                    refreshLedgerAndTransactions(null)
                    loadCustomersDirectory()
                    loadHistoryTab()
                    updatePrivacyUi()
                    Toast.makeText(this, "Data purged successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to purge data", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /**
     * Explicit user-triggered backup to SurrealDB Cloud.
     */
    fun triggerCloudBackup() {
        val unsynced = appOrchestrator.ledgerDatabase.getUnsyncedCount()
        updateSyncUi(SyncState.Syncing(unsynced))
        syncManager.performManualBackup { state ->
            runOnUiThread {
                updateSyncUi(state)
                if (state is SyncState.Success || state is SyncState.PartialFailure) {
                    refreshLedgerAndTransactions(currentFilteredCustomer)
                }
            }
        }
    }

    /**
     * Render the visual state of the cloud backup system according to SyncState.
     */
    fun updateSyncUi(state: SyncState) {
        when (state) {
            is SyncState.Idle -> {
                btnCloudSync.isEnabled = true
                pbSyncProgress.visibility = View.GONE
                if (state.unsyncedCount > 0) {
                    tvSyncStatus.text = "☁ SURREALDB BACKUP PENDING"
                    tvSyncSubtitle.text = "${state.unsyncedCount} unsynced local record(s) · Tap sync to backup"
                } else {
                    tvSyncStatus.text = "☁ SURREALDB CLOUD"
                    val timeStr = if (state.lastSyncedTimestamp != null) {
                        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(state.lastSyncedTimestamp))
                    } else "All records backed up"
                    tvSyncSubtitle.text = "● All records backed up · Last sync: $timeStr"
                }
            }
            is SyncState.Syncing -> {
                btnCloudSync.isEnabled = false
                pbSyncProgress.visibility = View.VISIBLE
                tvSyncStatus.text = "☁ BACKING UP TO CLOUD..."
                tvSyncSubtitle.text = "Uploading ${state.totalToSync} record(s) to SurrealDB Cloud..."
            }
            is SyncState.Success -> {
                btnCloudSync.isEnabled = true
                pbSyncProgress.visibility = View.GONE
                val timeStr = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(state.timestamp))
                tvSyncStatus.text = "☁ CLOUD BACKUP SUCCESSFUL"
                tvSyncSubtitle.text = "Synced: $timeStr (${state.syncedCount} uploaded, ${state.duplicateCount} duplicate acknowledged)"
            }
            is SyncState.PartialFailure -> {
                btnCloudSync.isEnabled = true
                pbSyncProgress.visibility = View.GONE
                tvSyncStatus.text = "⚠️ BACKUP PARTIAL FAILURE"
                tvSyncSubtitle.text = "${state.failedCount} records failed · Tap to retry (${state.errorMessage})"
            }
            is SyncState.Offline -> {
                btnCloudSync.isEnabled = true
                pbSyncProgress.visibility = View.GONE
                tvSyncStatus.text = "⚡ OFFLINE MODE"
                tvSyncSubtitle.text = "Device is offline. Local ledger is safe (${state.unsyncedCount} unsynced). Tap to retry when online."
            }
        }
    }

    private fun startListening() {
        releaseTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        cleanupClient()

        sessionActive = true
        val client = GnaniSttClient()
        gnaniSttClient = client

        client.setListener(object : SttListener {
            override fun onPartialTranscription(text: String) {
                runOnUiThread {
                    tvTranscript.text = text
                }
            }

            override fun onTranscriptionReceived(text: String) {
                runOnUiThread {
                    tvTranscript.text = text
                    tvStatus.text = "Transcript received: processing ledger entry…"
                    btnPushToTalk.text = "HOLD TO SPEAK"
                }
                appOrchestrator.processRealTransaction(text)
                mainHandler.postDelayed({ cleanupClient() }, 1200)
            }

            override fun onSttFailed() {
                runOnUiThread {
                    tvStatus.text = "Speech service failed. No entry was confirmed."
                    tvConnection.text = "●  Speech service error"
                    btnPushToTalk.text = "HOLD TO SPEAK"
                    cleanupClient()
                }
            }
        })

        client.onStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    SttState.DISCONNECTED -> tvConnection.text = "●  Ready — hold button to speak"
                    SttState.CONNECTING -> tvConnection.text = "●  Connecting to speech service…"
                    SttState.INITIALIZING -> tvConnection.text = "●  Initializing speech session…"
                    SttState.READY -> {
                        tvConnection.text = "●  Connected. Microphone active."
                        if (sessionActive) {
                            btnPushToTalk.text = "LISTENING…"
                            tvStatus.text = "Microphone active. Speak now."
                            audioPipeline.start()
                        }
                    }
                    SttState.STREAMING -> tvConnection.text = "●  Streaming speech to Gnani…"
                    SttState.CLOSING -> tvConnection.text = "●  Finalizing transcript…"
                    SttState.ERROR -> tvConnection.text = "●  Speech service error"
                }
            }
        }

        client.connectOnBackgroundThread()
    }

    private fun stopListening() {
        sessionActive = false
        audioPipeline.stop()

        val client = gnaniSttClient
        if (client != null && (client.currentState == SttState.READY || client.currentState == SttState.STREAMING)) {
            btnPushToTalk.text = "PROCESSING…"
            tvStatus.text = "Audio recorded. Transcribing with Gnani…"
            client.flushAndFinish()

            releaseTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            val timeoutRunnable = Runnable {
                if (gnaniSttClient == client) {
                    cleanupClient()
                    tvStatus.text = "Transcription timeout. Hold button to try again."
                    btnPushToTalk.text = "HOLD TO SPEAK"
                }
            }
            releaseTimeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, 4000)
        } else {
            cleanupClient()
            btnPushToTalk.text = "HOLD TO SPEAK"
        }
    }

    private fun cleanupClient() {
        releaseTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        releaseTimeoutRunnable = null
        val client = gnaniSttClient
        gnaniSttClient = null
        try {
            client?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing STT client: ${e.message}", e)
        }
        tvConnection.text = "●  Ready — hold button to speak"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                btnPushToTalk.isEnabled = true
                tvStatus.text = "Microphone permission granted. Hold the button to speak."
            } else {
                btnPushToTalk.isEnabled = false
                tvStatus.text = "Microphone permission is required."
                Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runMilestone2Verification() {
        Log.i("KHATAWAS_M2_VERIFICATION", "=== BEGIN MILESTONE 2 JNI RUNTIME VERIFICATION ===")
        val isLoaded = com.khatawas.rust.RustEngine.isNativeLoaded()
        Log.i("KHATAWAS_M2_VERIFICATION", "Test A [Library Loading]: isNativeLoaded = $isLoaded")

        if (isLoaded) {
            try {
                val version = com.khatawas.rust.RustEngine.nativeGetVersion()
                Log.i("KHATAWAS_M2_VERIFICATION", "Test B [Native Version]: $version")

                val addStandard = com.khatawas.rust.RustEngine.nativeAdd(20, 22)
                val addNegative = com.khatawas.rust.RustEngine.nativeAdd(-5, 5)
                val addBoundary = com.khatawas.rust.RustEngine.nativeAdd(Int.MAX_VALUE, 1)
                Log.i("KHATAWAS_M2_VERIFICATION", "Test C [Native Arithmetic]: 20 + 22 = $addStandard, -5 + 5 = $addNegative, MAX + 1 = $addBoundary")

                val health = com.khatawas.rust.RustEngine.nativeHealthCheck()
                Log.i("KHATAWAS_M2_VERIFICATION", "Test D [Health Check]: $health")

                var allCorrect = true
                for (i in 0 until 1000) {
                    if (com.khatawas.rust.RustEngine.nativeAdd(i, 1) != i + 1) {
                        allCorrect = false
                        break
                    }
                }
                Log.i("KHATAWAS_M2_VERIFICATION", "Test E [Repeated Invocation]: 1000 iterations successful = $allCorrect")
            } catch (e: Throwable) {
                Log.e("KHATAWAS_M2_VERIFICATION", "JNI Execution Exception", e)
            }
        }
        Log.i("KHATAWAS_M2_VERIFICATION", "=== END MILESTONE 2 JNI RUNTIME VERIFICATION ===")
    }

    private fun runMilestone3Verification() {
        Log.i("KHATAWAS_M3_VERIFICATION", "=== BEGIN MILESTONE 3 FIR RUNTIME VERIFICATION ===")
        try {
            val rustFir = com.khatawas.audio.RustFirDecimator()
            Log.i("KHATAWAS_M3_VERIFICATION", "RustFirDecimator initialized, isNativeActive = ${rustFir.isNativeActive}")

            val input = FloatArray(480) { 1000.0f }
            val output = FloatArray(160)
            val written = rustFir.process(input, output)
            Log.i("KHATAWAS_M3_VERIFICATION", "Processed 480 input samples -> $written output samples, out[0]=${output[0]}, out[159]=${output[159]}")

            rustFir.reset()
            Log.i("KHATAWAS_M3_VERIFICATION", "RustFirDecimator reset successful")
            rustFir.close()
            Log.i("KHATAWAS_M3_VERIFICATION", "RustFirDecimator closed cleanly, isNativeActive = ${rustFir.isNativeActive}")
        } catch (e: Throwable) {
            Log.e("KHATAWAS_M3_VERIFICATION", "Milestone 3 runtime verification failed", e)
        }
        Log.i("KHATAWAS_M3_VERIFICATION", "=== END MILESTONE 3 FIR RUNTIME VERIFICATION ===")
    }

    private fun runMilestone4Verification() {
        Log.i("KHATAWAS_M4_VERIFICATION", "=== BEGIN MILESTONE 4 AUDIO PIPELINE RUNTIME VERIFICATION ===")
        try {
            val audioCore = com.khatawas.audio.RustAudioPipelineCore()
            Log.i("KHATAWAS_M4_VERIFICATION", "RustAudioPipelineCore initialized, isNativeActive = ${audioCore.isNativeActive}")

            val inFloats = FloatArray(480) { 1000.0f }
            val outBytes = ByteArray(320)
            val written = audioCore.processFloatFrame(inFloats, outBytes)
            val frames = audioCore.getProcessedFrames()
            Log.i("KHATAWAS_M4_VERIFICATION", "Processed 480 floats -> $written PCM16 bytes, totalFrames = $frames, byte[0]=${outBytes[0]}, byte[319]=${outBytes[319]}")

            audioCore.reset()
            Log.i("KHATAWAS_M4_VERIFICATION", "AudioPipelineCore reset successful")
            audioCore.close()
            Log.i("KHATAWAS_M4_VERIFICATION", "AudioPipelineCore closed cleanly, isNativeActive = ${audioCore.isNativeActive}")
        } catch (e: Throwable) {
            Log.e("KHATAWAS_M4_VERIFICATION", "Milestone 4 runtime verification failed", e)
        }
        Log.i("KHATAWAS_M4_VERIFICATION", "=== END MILESTONE 4 AUDIO PIPELINE RUNTIME VERIFICATION ===")
    }

    private fun runMilestone5Verification() {
        Log.i("KHATAWAS_M5_VERIFICATION", "=== BEGIN MILESTONE 5 AUDIO DENOISING RUNTIME VERIFICATION ===")
        try {
            val denoiser = com.khatawas.audio.RustDenoiseProcessor(com.khatawas.audio.RustDenoiseProcessor.MODE_WIENER_FILTER)
            Log.i("KHATAWAS_M5_VERIFICATION", "RustDenoiseProcessor initialized, isAvailable = ${denoiser.isAvailable}")

            val inFloats = FloatArray(480) { 800.0f }
            val outFloats = FloatArray(480)
            val written = denoiser.processFrame(inFloats, outFloats)
            Log.i("KHATAWAS_M5_VERIFICATION", "Processed 480 floats -> $written output samples, out[0]=${outFloats[0]}, out[479]=${outFloats[479]}")

            val modeChanged = denoiser.setMode(com.khatawas.audio.RustDenoiseProcessor.MODE_SPECTRAL_SUBTRACTION)
            Log.i("KHATAWAS_M5_VERIFICATION", "Changed mode to Spectral Subtraction: $modeChanged")

            val resetSuccess = denoiser.reset()
            Log.i("KHATAWAS_M5_VERIFICATION", "RustDenoiseProcessor reset successful: $resetSuccess")

            denoiser.close()
            Log.i("KHATAWAS_M5_VERIFICATION", "RustDenoiseProcessor closed cleanly, isAvailable = ${denoiser.isAvailable}")
        } catch (e: Throwable) {
            Log.e("KHATAWAS_M5_VERIFICATION", "Milestone 5 runtime verification failed", e)
        }
        Log.i("KHATAWAS_M5_VERIFICATION", "=== END MILESTONE 5 AUDIO DENOISING RUNTIME VERIFICATION ===")
    }

    /**
     * Refresh customer ledger and recent transaction list asynchronously from local encrypted SQLite.
     */
    fun refreshLedgerAndTransactions(filterCustomer: String? = null) {
        currentFilteredCustomer = filterCustomer
        bgExecutor.execute {
            try {
                val db = appOrchestrator.ledgerDatabase
                val entities = if (filterCustomer.isNullOrBlank()) {
                    db.getRecentTransactions(100)
                } else {
                    db.getTransactionsForCustomer(filterCustomer, 100)
                }

                val balancePaise = if (filterCustomer.isNullOrBlank()) {
                    val topDebtors = db.getTopDebtors(100)
                    topDebtors.sumOf { it.second }
                } else {
                    db.getCustomerBalancePaise(filterCustomer)
                }

                val uiModels = entities.map { entity ->
                    TransactionUiModel(
                        id = entity.id,
                        eventId = entity.eventId,
                        customerName = entity.customerName,
                        amountPaise = entity.amountPaise,
                        category = TransactionCategory.fromRecord(entity.type, entity.note),
                        timestamp = entity.timestamp,
                        note = entity.note,
                        paymentMethod = if (entity.note.contains("UPI", ignoreCase = true)) "UPI / Bank" else "Cash / Khata",
                        reviewStatus = "Recorded"
                    )
                }

                runOnUiThread {
                    cachedBalancePaise = balancePaise
                    if (filterCustomer.isNullOrBlank()) {
                        tvLedgerBalanceSubtitle.text = "Authoritative outstanding balance across all customers."
                        btnFilterReset.visibility = View.GONE
                    } else {
                        tvLedgerBalanceSubtitle.text = "Net outstanding udhaar balance for $filterCustomer."
                        btnFilterReset.visibility = View.VISIBLE
                    }

                    transactionAdapter.submitList(uiModels)

                    if (uiModels.isEmpty()) {
                        llEmptyState.visibility = View.VISIBLE
                        rvTransactions.visibility = View.GONE
                    } else {
                        llEmptyState.visibility = View.GONE
                        rvTransactions.visibility = View.VISIBLE
                    }

                    updatePrivacyUi()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing ledger transactions: ${e.message}", e)
            }
        }
    }

    /**
     * Updates all UI components to reflect locked or unlocked privacy state.
     */
    fun updatePrivacyUi() {
        val isUnlocked = PrivacyManager.isUnlocked
        val maskNames = PrivacyManager.isMaskCustomerNamesEnabled(this)

        if (isUnlocked) {
            tvPrivacyStatus.text = "🔓 UNLOCKED"
            tvPrivacySubtitle.text = "Tap LOCK to mask all financial values"
            btnAuthAction.text = "LOCK"
            btnAuthAction.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary))

            tvResult.text = cachedLatestAmountString
            tvResult.contentDescription = cachedLatestAmountString
            tvResultDetail.text = cachedLatestDetailString
            tvResultDetail.contentDescription = cachedLatestDetailString

            tvLedgerBalance.text = IndianCurrencyFormatter.formatPaise(cachedBalancePaise)
            tvLedgerBalance.contentDescription = IndianCurrencyFormatter.formatPaise(cachedBalancePaise)
            if (cachedBalancePaise > 0L) {
                tvLedgerBalance.setTextColor(ContextCompat.getColor(this, R.color.btn_credit_bg))
            } else if (cachedBalancePaise < 0L) {
                tvLedgerBalance.setTextColor(ContextCompat.getColor(this, R.color.btn_debit_bg))
            } else {
                tvLedgerBalance.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }

            val customerLabel = currentFilteredCustomer ?: "All Customers"
            tvLedgerCustomerName.text = customerLabel
            tvLedgerCustomerName.contentDescription = customerLabel
        } else {
            tvPrivacyStatus.text = "🔒 PRIVACY LOCKED"
            tvPrivacySubtitle.text = "Financial values masked with ###XXX"
            btnAuthAction.text = "UNLOCK"
            btnAuthAction.setBackgroundColor(Color.parseColor("#2457D6"))

            tvResult.text = PrivacyManager.MASK_VALUE
            tvResult.contentDescription = PrivacyManager.ACCESSIBLE_AMOUNT_HIDDEN
            tvResultDetail.text = PrivacyManager.ACCESSIBLE_DETAILS_HIDDEN
            tvResultDetail.contentDescription = PrivacyManager.ACCESSIBLE_DETAILS_HIDDEN

            tvLedgerBalance.text = PrivacyManager.MASK_VALUE
            tvLedgerBalance.contentDescription = PrivacyManager.ACCESSIBLE_BALANCE_HIDDEN

            val customerLabel = if (maskNames) PrivacyManager.MASK_VALUE else currentFilteredCustomer ?: "All Customers"
            tvLedgerCustomerName.text = customerLabel
            tvLedgerCustomerName.contentDescription = if (maskNames) PrivacyManager.ACCESSIBLE_NAME_HIDDEN else customerLabel
        }

        transactionAdapter.isUnlocked = isUnlocked
        transactionAdapter.maskCustomerNames = maskNames
        transactionAdapter.notifyDataSetChanged()

        if (::customerAdapter.isInitialized) {
            customerAdapter.isUnlocked = isUnlocked
            customerAdapter.maskCustomerNames = maskNames
            customerAdapter.notifyDataSetChanged()
        }

        if (::historyTransactionAdapter.isInitialized) {
            historyTransactionAdapter.isUnlocked = isUnlocked
            historyTransactionAdapter.maskCustomerNames = maskNames
            historyTransactionAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Handles explicit unlock and lock actions.
     */
    private fun handleAuthAction() {
        if (PrivacyManager.isUnlocked) {
            PrivacyManager.lockSession()
            updatePrivacyUi()
            refreshLedgerAndTransactions(currentFilteredCustomer)
            Toast.makeText(this, "Privacy mode locked", Toast.LENGTH_SHORT).show()
        } else {
            if (BiometricAuthHelper.canAuthenticateWithBiometrics(this)) {
                BiometricAuthHelper.authenticate(
                    activity = this,
                    title = "Unlock KHATAWAS",
                    subtitle = "Verify fingerprint or face to unmask financial data",
                    onSuccess = {
                        runOnUiThread {
                            lastInteractionTimestamp = System.currentTimeMillis()
                            updatePrivacyUi()
                            refreshLedgerAndTransactions(currentFilteredCustomer)
                            Toast.makeText(this@MainActivity, "Unlocked successfully", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = {
                        runOnUiThread {
                            promptPinAuthentication()
                        }
                    },
                    onUsePinFallback = {
                        runOnUiThread {
                            promptPinAuthentication()
                        }
                    }
                )
            } else {
                promptPinAuthentication()
            }
        }
    }

    private fun promptPinAuthentication() {
        if (!PrivacyManager.hasConfiguredPin(this)) {
            promptPinSetup("Create Administrator PIN", "Set a 4 to 8 digit administrator PIN to protect your ledger data.")
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter 4-8 digit PIN"
            setPadding(40, 30, 40, 30)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Enter Administrator PIN")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val enteredPin = input.text.toString()
                if (PrivacyManager.verifyPin(this, enteredPin)) {
                    lastInteractionTimestamp = System.currentTimeMillis()
                    updatePrivacyUi()
                    refreshLedgerAndTransactions(currentFilteredCustomer)
                    Toast.makeText(this, "Unlocked with PIN", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Incorrect PIN. Ledger remains locked.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPinSetup(title: String, message: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter 4-8 digit PIN"
            setPadding(40, 30, 40, 30)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Set PIN") { _, _ ->
                val enteredPin = input.text.toString()
                if (PrivacyManager.configurePin(this, enteredPin)) {
                    PrivacyManager.unlockSession()
                    lastInteractionTimestamp = System.currentTimeMillis()
                    updatePrivacyUi()
                    refreshLedgerAndTransactions(currentFilteredCustomer)
                    Toast.makeText(this, "Administrator PIN configured successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be between 4 and 8 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPrivacyConfigDialog() {
        val options = arrayOf(
            if (PrivacyManager.hasConfiguredPin(this)) "Change Administrator PIN" else "Set Administrator PIN",
            if (PrivacyManager.isMaskCustomerNamesEnabled(this)) "Customer Names: Masked (###XXX)" else "Customer Names: Visible",
            "Inactivity Lock: ${PrivacyManager.getInactivityTimeoutMs(this) / 1000}s"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Privacy & Security Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptPinSetup("Configure Administrator PIN", "Enter a 4 to 8 digit administrator PIN.")
                    1 -> {
                        val current = PrivacyManager.isMaskCustomerNamesEnabled(this)
                        PrivacyManager.setMaskCustomerNamesEnabled(this, !current)
                        updatePrivacyUi()
                        refreshLedgerAndTransactions(currentFilteredCustomer)
                        val status = if (!current) "Customer names will be masked" else "Customer names will be visible"
                        Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val timeoutOptions = arrayOf("30 seconds", "60 seconds", "2 minutes", "5 minutes")
                        val timeoutValues = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Select Inactivity Lock Timeout")
                            .setItems(timeoutOptions) { _, idx ->
                                PrivacyManager.setInactivityTimeoutMs(this, timeoutValues[idx])
                                Toast.makeText(this, "Inactivity timeout set to ${timeoutOptions[idx]}", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                }
            }
            .setPositiveButton("Done", null)
            .show()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionTimestamp = System.currentTimeMillis()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("KEY_ACTIVE_TAB", currentTab)
    }

    override fun onPause() {
        super.onPause()
        PrivacyManager.lockSession()
        updatePrivacyUi()
        mainHandler.removeCallbacks(inactivityCheckRunnable)
    }

    override fun onStop() {
        super.onStop()
        PrivacyManager.lockSession()
    }

    override fun onResume() {
        super.onResume()
        updatePrivacyUi()
        updateLanguageSelectionUi(LanguageManager.getLanguage(this))
        mainHandler.removeCallbacks(inactivityCheckRunnable)
        mainHandler.postDelayed(inactivityCheckRunnable, 5000L)
    }

    override fun onDestroy() {
        sessionActive = false
        audioPipeline.stop()
        cleanupClient()
        syncManager.shutdown()
        appOrchestrator.destroy()
        bgExecutor.shutdown()
        mainHandler.removeCallbacks(inactivityCheckRunnable)
        super.onDestroy()
    }
}
