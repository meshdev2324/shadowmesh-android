package com.shadowmesh.core_vpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SecureStorageTest {

    private lateinit var encryptedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        encryptedPrefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { encryptedPrefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } just Runs
        
        SecureStorage.resetInstance()
    }

    @After
    fun tearDown() {
        unmockkAll()
        SecureStorage.resetInstance()
    }

    private fun getStorageWithMockPrefs(): SecureStorage {
        val storage = SecureStorage.createForTest(context, encryptedPrefs)
        SecureStorage.setInstance(storage)
        return storage
    }

    @Test
    fun `set should call putString`() {
        val storage = getStorageWithMockPrefs()
        storage.set("key", "value")
        verify { editor.putString("key", "value") }
    }

    @Test
    fun `set with null value should call remove`() {
        val storage = getStorageWithMockPrefs()
        storage.set("key", null)
        verify { editor.remove("key") }
    }

    @Test
    fun `get should call getString`() {
        val storage = getStorageWithMockPrefs()
        every { encryptedPrefs.getString("key", null) } returns "value"
        assertEquals("value", storage.get("key"))
    }

    @Test
    fun `remove should call remove and apply`() {
        val storage = getStorageWithMockPrefs()
        storage.remove("key")
        verify { editor.remove("key") }
        verify { editor.apply() }
    }

    @Test
    fun `generateSalt should return 64 character hex string`() {
        val storage = getStorageWithMockPrefs()
        val salt = storage.generateSalt()
        assertEquals(64, salt.length)
        assertTrue(salt.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `hashPin should return consistent hash`() {
        val storage = getStorageWithMockPrefs()
        val pin = "1234"
        val salt = "somesalt"
        val hash1 = storage.hashPin(pin, salt)
        val hash2 = storage.hashPin(pin, salt)
        assertEquals(hash1, hash2)
        assertNotEquals(pin, hash1)
    }

    @Test
    fun `getPersistentDeviceId should generate and save if not exists`() {
        val storage = getStorageWithMockPrefs()
        every { encryptedPrefs.getString("device_id", null) } returns null
        
        val deviceId = storage.getPersistentDeviceId()
        
        assertNotNull(deviceId)
        verify { editor.putString("device_id", any()) }
        verify { editor.apply() }
    }

    @Test
    fun `getPersistentDeviceId should return existing if exists`() {
        val storage = getStorageWithMockPrefs()
        every { encryptedPrefs.getString("device_id", null) } returns "existing-id"
        
        val deviceId = storage.getPersistentDeviceId()
        
        assertEquals("existing-id", deviceId)
        verify(exactly = 0) { editor.putString("device_id", any()) }
    }
}
