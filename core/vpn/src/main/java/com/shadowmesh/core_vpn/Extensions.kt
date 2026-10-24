package com.shadowmesh.core_vpn

import uniffi.shadowmesh.ApiClient
import uniffi.shadowmesh.VpnManager
import android.util.Log

/**
 * Technical Gap Fillers (SOP 13 §2).
 * These methods are called by the UI layer but may not be fully implemented 
 * in the current Rust UniFFI layer. We provide these extensions to maintain
 * build integrity while documenting forensic requirements.
 */

private const val TAG = "ShadowMeshExtensions"


