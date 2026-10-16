package com.shadowmesh.core_vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

interface NetworkClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String?
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): Int
}

@Singleton
class NetworkClientImpl @Inject constructor() : NetworkClient {
    override suspend fun get(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                for ((name, value) in headers) conn.setRequestProperty(name, value)
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>
    ): Int = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            // Applied after Content-Type so a caller cannot silently drop it.
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray())
            conn.responseCode
        } catch (e: Exception) {
            -1
        }
    }
}
