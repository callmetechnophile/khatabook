package com.khatawas

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.khatawas.audio.RnnoiseProcessor
import com.khatawas.db.LedgerDatabase
import com.khatawas.rust.RustEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RustEngineInstrumentedTest {

    @Test
    fun testA_NativeLibraryLoading() {
        assertTrue("libkhatawas_android.so should be loaded in Android runtime", RustEngine.isNativeLoaded())
    }

    @Test
    fun testB_NativeVersion() {
        val version = RustEngine.nativeGetVersion()
        assertNotNull("Version string must not be null", version)
        assertEquals("KHATAWAS-Core-v0.1.0", version)
    }

    @Test
    fun testC_NativeArithmetic() {
        // Standard addition
        assertEquals(42, RustEngine.nativeAdd(20, 22))

        // Negative integer addition
        assertEquals(0, RustEngine.nativeAdd(-5, 5))
        assertEquals(-50, RustEngine.nativeAdd(-20, -30))

        // Saturating addition boundary check
        assertEquals(Int.MAX_VALUE, RustEngine.nativeAdd(Int.MAX_VALUE, 1))
        assertEquals(Int.MIN_VALUE, RustEngine.nativeAdd(Int.MIN_VALUE, -1))
    }

    @Test
    fun testD_NativeHealthCheck() {
        assertTrue("nativeHealthCheck should return true", RustEngine.nativeHealthCheck())
    }

    @Test
    fun testE_RepeatedInvocationStability() {
        for (i in 0 until 1000) {
            val result = RustEngine.nativeAdd(i, 1)
            assertEquals(i + 1, result)
        }
    }

    @Test
    fun testRegression_RnnoiseCoexistence() {
        // Verify existing C/C++ RNNoise library (libkhatawas_jni.so) loads and operates
        val processor = RnnoiseProcessor()
        assertTrue("RNNoise C++ library should be available in runtime", processor.isAvailable)
        processor.destroy()
    }

    @Test
    fun testRegression_SqlcipherCoexistence() {
        // Verify existing SQLCipher database (libsqlcipher.so) initializes securely
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = LedgerDatabase(context)
        val readableDb = db.getReadableEncryptedDatabase()
        assertNotNull("SQLCipher database handle must be valid", readableDb)
        assertTrue("SQLCipher database must be open", readableDb.isOpen)
    }
}
