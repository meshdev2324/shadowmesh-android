package com.shadowmesh.core_vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

interface NetworkClient {
    suspend fun get(url: String): String?
    suspend fun post(url: String, body: String): Int
}

@Singleton
class NetworkClientImpl @Inject constructor() : NetworkClient {
    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun post(url: String, body: String): Int = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray())
            conn.responseCode
        } catch (e: Exception) {
            -1
        }
    }
}
