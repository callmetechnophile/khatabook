package com.khatawas.rust

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying RustEngine JNI declarations and state in the test runner.
 */
class RustEngineIntegrationTest {

    @Test
    fun testRustEngineClassInitialization() {
        // Verify object can be accessed without uncaught classloader exceptions
        assertNotNull(RustEngine)
    }

    @Test
    fun testNativeSignaturesPresent() {
        val methods = RustEngine::class.java.declaredMethods
        val methodNames = methods.map { it.name }

        assertTrue(methodNames.contains("nativeGetVersion"))
        assertTrue(methodNames.contains("nativeAdd"))
        assertTrue(methodNames.contains("nativeHealthCheck"))
        assertTrue(methodNames.contains("nativeFirProcess"))
        assertTrue(methodNames.contains("nativeParseUtterance"))
        assertTrue(methodNames.contains("nativeCalculateBalance"))
    }
}
