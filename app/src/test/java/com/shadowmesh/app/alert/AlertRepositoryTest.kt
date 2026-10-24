package com.shadowmesh.app.alert

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the RFC-028 alert channel: one alert per tag, severity
 * ordering, transient expiry, and sticky survival.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertRepositoryTest {

    private fun repo(scope: TestScope) = AlertRepository(scope)

    @Test
    fun `same tag replaces rather than stacks`() = runTest {
        val repo = repo(this)
        repo.post(AlertSeverity.INFO, AlertTag.NETWORK, "first")
        repo.post(AlertSeverity.WARNING, AlertTag.NETWORK, "second")
        assertEquals(1, repo.alerts.value.size)
        assertEquals("second", repo.alerts.value.single().message)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `different tags coexist`() = runTest {
        val repo = repo(this)
        repo.post(AlertSeverity.INFO, AlertTag.NETWORK, "net")
        repo.post(AlertSeverity.CRITICAL, AlertTag.TUNNEL, "tun")
        assertEquals(2, repo.alerts.value.size)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `transient alert expires after the auto-dismiss window`() = runTest {
        val repo = repo(this)
        repo.post(AlertSeverity.WARNING, AlertTag.NETWORK, "temp")
        assertEquals(1, repo.alerts.value.size)
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(1, repo.alerts.value.size)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(repo.alerts.value.isEmpty())
    }

    @Test
    fun `sticky alert survives the auto-dismiss window`() = runTest {
        val repo = repo(this)
        repo.post(AlertSeverity.CRITICAL, AlertTag.SESSION, "decide", sticky = true)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, repo.alerts.value.size)
        repo.dismiss(repo.alerts.value.single().id)
        assertTrue(repo.alerts.value.isEmpty())
    }

    @Test
    fun `dismissTransient keeps sticky alerts`() = runTest {
        val repo = repo(this)
        repo.post(AlertSeverity.INFO, AlertTag.NETWORK, "transient")
        repo.post(AlertSeverity.CRITICAL, AlertTag.SECURITY, "sticky", sticky = true)
        repo.dismissTransient()
        assertEquals(1, repo.alerts.value.size)
        assertEquals(AlertTag.SECURITY, repo.alerts.value.single().tag)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `messages are sanitized at construction`() = runTest {
        val repo = repo(this)
        repo.post(
            AlertSeverity.CRITICAL,
            AlertTag.TUNNEL,
            "Cannot reach https://edge.example.com:443 via 10.0.0.97 token a1b2c3d4e5f6a7b8",
        )
        val message = repo.alerts.value.single().message
        assertFalse(message.contains("10.0.0.97"))
        assertFalse(message.contains("edge.example.com"))
        assertFalse(message.contains("a1b2c3d4e5f6a7b8"))
        assertTrue(message.contains("Cannot reach"))
        coroutineContext.cancelChildren()
    }

    @Test
    fun `blank sanitized message degrades to a fixed readable line`() {
        val alert = Alert.create(AlertSeverity.INFO, AlertTag.NETWORK, "10.0.0.97")
        assertNull(null)
        assertFalse(alert.message.contains("10.0.0.97"))
        assertTrue(alert.message.isNotBlank())
    }
}
