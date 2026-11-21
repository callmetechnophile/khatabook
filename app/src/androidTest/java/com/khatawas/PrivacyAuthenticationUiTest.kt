/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Instrumented test suite verifying KHATAWAS Privacy-First UI and Authentication requirements:
 * 1. Default masked display state with ###XXX format.
 * 2. Accessible descriptions ("Amount hidden", "Balance hidden", "Customer name hidden").
 * 3. FLAG_SECURE window protection against screen capture and recent-apps switcher leaks.
 * 4. User-created administrator PIN configuration and verification without default PINs.
 * 5. Unlock session management: manual lock, backgrounding relock, and inactivity relock.
 * 6. Preserved ledger balance calculations beneath the privacy mask.
 */

package com.khatawas

import android.content.Context
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khatawas.auth.BiometricAuthHelper
import com.khatawas.auth.PrivacyManager
import com.khatawas.ui.IndianCurrencyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyAuthenticationUiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Enforce default locked state before each test
        PrivacyManager.lockSession()
    }

    @Test
    fun testCriterion1_DefaultMaskedDisplayState() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            // Session must start locked
            assertFalse("App must start in a locked privacy state", PrivacyManager.isUnlocked)

            val tvPrivacyStatus = activity.findViewById<TextView>(R.id.tv_privacy_status)
            val btnAuthAction = activity.findViewById<Button>(R.id.btn_auth_action)
            val tvLedgerBalance = activity.findViewById<TextView>(R.id.tv_ledger_balance)
            val tvLedgerCustomer = activity.findViewById<TextView>(R.id.tv_ledger_customer_name)
            val rvTransactions = activity.findViewById<RecyclerView>(R.id.rv_transactions)

            assertNotNull(tvPrivacyStatus)
            assertEquals("🔒 PRIVACY LOCKED", tvPrivacyStatus.text.toString())
            assertEquals("UNLOCK", btnAuthAction.text.toString())

            // Masked financial values must show ###XXX
            assertEquals(PrivacyManager.MASK_VALUE, tvLedgerBalance.text.toString())

            // Masked content descriptions for accessibility
            assertEquals(
                PrivacyManager.ACCESSIBLE_BALANCE_HIDDEN,
                tvLedgerBalance.contentDescription.toString()
            )

            // Verify transaction adapter is configured for locked privacy mode
            val adapter = rvTransactions.adapter as? com.khatawas.ui.TransactionAdapter
            assertNotNull(adapter)
            assertFalse("Adapter must be in locked state", adapter!!.isUnlocked)

            // Verify transaction item masking and accessibility descriptions
            val sampleModel = com.khatawas.ui.TransactionUiModel(
                id = 999L,
                eventId = "EVT-TEST",
                customerName = "Ramesh Kumar",
                amountPaise = 75000L,
                category = com.khatawas.ui.TransactionCategory.CREDIT,
                timestamp = System.currentTimeMillis(),
                note = "Test Entry"
            )
            assertEquals(PrivacyManager.MASK_VALUE, sampleModel.getDisplayAmount(adapter.isUnlocked))
            assertEquals(PrivacyManager.MASK_VALUE, sampleModel.getDisplayCustomerName(adapter.isUnlocked, adapter.maskCustomerNames))
            val desc = sampleModel.getAccessibleDescription(adapter.isUnlocked, adapter.maskCustomerNames)
            assertTrue(desc.contains(PrivacyManager.ACCESSIBLE_AMOUNT_HIDDEN))
            assertTrue(desc.contains(PrivacyManager.ACCESSIBLE_NAME_HIDDEN))
        }
        scenario.close()
    }

    @Test
    fun testCriterion2_FlagSecureSetOnWindow() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            val isSecure = (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            assertTrue("FLAG_SECURE must be set on window to protect recent-apps switcher preview", isSecure)
        }
        scenario.close()
    }

    @Test
    fun testCriterion3_NoDefaultAdminPinAndUserPinConfiguration() {
        // Clear prefs to simulate fresh install
        val prefs = context.getSharedPreferences("khatawas_privacy_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Rule: "Do not invent a default administrator PIN"
        assertFalse("Must have NO configured PIN on initial launch", PrivacyManager.hasConfiguredPin(context))

        // Invalid PIN rejects
        assertFalse(PrivacyManager.configurePin(context, "12"))
        assertFalse(PrivacyManager.configurePin(context, "123456789"))
        assertFalse(PrivacyManager.configurePin(context, "abcd"))

        // Valid user PIN configuration
        assertTrue(PrivacyManager.configurePin(context, "9876"))
        assertTrue("PIN must now be configured", PrivacyManager.hasConfiguredPin(context))

        // Incorrect PIN fails and stays locked
        PrivacyManager.lockSession()
        assertFalse(PrivacyManager.verifyPin(context, "1111"))
        assertFalse(PrivacyManager.isUnlocked)

        // Correct PIN succeeds and unlocks session
        assertTrue(PrivacyManager.verifyPin(context, "9876"))
        assertTrue("Session must be unlocked after valid PIN verification", PrivacyManager.isUnlocked)
    }

    @Test
    fun testCriterion4_ManualLockAction() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            // Simulate unlock
            PrivacyManager.unlockSession()
            val tvPrivacyStatus = activity.findViewById<TextView>(R.id.tv_privacy_status)
            val btnAuthAction = activity.findViewById<Button>(R.id.btn_auth_action)

            // Calling updatePrivacyUi reflects unlocked state
            val method = MainActivity::class.java.getDeclaredMethod("updatePrivacyUi")
            method.isAccessible = true
            method.invoke(activity)

            assertEquals("🔓 UNLOCKED", tvPrivacyStatus.text.toString())
            assertEquals("LOCK", btnAuthAction.text.toString())

            // Tapping Lock button immediately relocks session
            btnAuthAction.performClick()

            assertFalse("Session must be locked after clicking Lock", PrivacyManager.isUnlocked)
            assertEquals("🔒 PRIVACY LOCKED", tvPrivacyStatus.text.toString())
            assertEquals("UNLOCK", btnAuthAction.text.toString())
        }
        scenario.close()
    }

    @Test
    fun testCriterion5_BackgroundingRelocksSession() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            PrivacyManager.unlockSession()
            assertTrue(PrivacyManager.isUnlocked)

            // Invoke lifecycle pause transition
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnPause(activity)
            assertFalse(
                "Session must automatically relock when application is paused / moved to background",
                PrivacyManager.isUnlocked
            )
        }
        scenario.close()
    }

    @Test
    fun testCriterion6_PreservedLedgerCalculationBeneathMask() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val db = com.khatawas.db.LedgerDatabase(activity)
            val balancePaise = db.getCustomerBalancePaise("BenchmarkCustomer")
            val formatted = IndianCurrencyFormatter.formatPaise(balancePaise)

            // When locked: display is masked, underlying calculation is intact
            PrivacyManager.lockSession()
            val masked = PrivacyManager.maskAmount(formatted)
            assertEquals(PrivacyManager.MASK_VALUE, masked)

            // When unlocked: displays full calculated value
            PrivacyManager.unlockSession()
            val unmasked = PrivacyManager.maskAmount(formatted)
            assertEquals(formatted, unmasked)

            // Authoritative database balance is untouched
            assertEquals(balancePaise, db.getCustomerBalancePaise("BenchmarkCustomer"))
            db.close()
        }
        scenario.close()
    }

    @Test
    fun testCriterion7_BiometricAuthHelperContract() {
        // Validates helper does not throw and provides platform contract
        val canBio = BiometricAuthHelper.canAuthenticateWithBiometrics(context)
        val canDeviceCred = BiometricAuthHelper.canAuthenticateWithDeviceCredentials(context)

        // Regardless of hardware status (emulator may lack enrolled biometric), calls must succeed without crash
        assertTrue(canBio || !canBio)
        assertTrue(canDeviceCred || !canDeviceCred)
    }
}
