package com.shadowmesh.app.util

import android.util.Log
import java.util.regex.Pattern

/**
 * Zero-PII Logger for ShadowMesh.
 * Automatically scrubs IP addresses, tokens, and activation codes from logs.
 * SOP 11 §4: Forensic resistance at the logcat layer.
 */
object ZLog {
    private const val GLOBAL_TAG = "ShadowMesh"
    
    private val IPV4_PATTERN = Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b")
    private val IPV6_PATTERN = Pattern.compile("\\b([0-9a-fA-F]{1,4}:[0-9a-fA-F]{1,4}):[0-9a-fA-F:]+\\b")
    private val CODE_PATTERN = Pattern.compile("\\b[A-Z0-9]{25}\\b")
    private val JWT_PATTERN = Pattern.compile("eyJ[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+){0,2}")
    
    fun v(tag: String, msg: String) = Log.v(tag, scrub(msg))
    fun d(tag: String, msg: String) = Log.d(tag, scrub(msg))
    fun i(tag: String, msg: String) = Log.i(tag, scrub(msg))
    fun w(tag: String, msg: String) = Log.w(tag, scrub(msg))
    fun e(tag: String, msg: String, tr: Throwable? = null) = Log.e(tag, scrub(msg), tr)

    /**
     * Scrubs Personally Identifiable Information (PII) from log messages.
     */
    fun scrub(msg: String): String {
        var s = IPV4_PATTERN.matcher(msg).replaceAll("[REDACTED_IP]")
        s = IPV6_PATTERN.matcher(s).replaceAll("[REDACTED_IP]")
        s = CODE_PATTERN.matcher(s).replaceAll("[REDACTED_CODE]")
        s = JWT_PATTERN.matcher(s).replaceAll("[MASKED_TOKEN]")
        return s
    }
}
