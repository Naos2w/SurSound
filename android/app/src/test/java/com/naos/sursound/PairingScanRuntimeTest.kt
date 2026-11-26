package com.naos.sursound

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class PairingScanRuntimeTest {
    @Test fun scannerDependenciesLoadFromRuntimeClasspath() {
        PairingScan.verifyRuntime(javaClass.classLoader!!)
    }

    @Test fun missingRuntimeIsReportedBeforeLaunchingScanner() {
        val missingCore = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "androidx.core.content.ContextCompat") {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }
        try {
            PairingScan.verifyRuntime(missingCore)
            fail("Scanner preflight should reject missing runtime")
        } catch (e: IllegalStateException) {
            assertTrue(e.cause is ClassNotFoundException)
        }
    }
}
