package com.azusachino.cappuccino

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkSecurityTest {
  @Test
  fun debugCleartextIsLoopbackOnly() {
    val policy = NetworkSecurityPolicy.getInstance()
    assertTrue(policy.isCleartextTrafficPermitted("localhost"))
    assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
    assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"))
    assertTrue(policy.isCleartextTrafficPermitted("::1"))
    assertFalse(policy.isCleartextTrafficPermitted("bridge.example"))
  }
}
