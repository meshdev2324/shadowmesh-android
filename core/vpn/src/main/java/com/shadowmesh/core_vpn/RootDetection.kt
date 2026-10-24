package com.shadowmesh.core_vpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

interface FileSystem {
    fun exists(path: String): Boolean
    fun canWrite(path: String): Boolean
    fun execute(command: Array<String>): Process
}

class RealFileSystem : FileSystem {
    override fun exists(path: String): Boolean = File(path).exists()
    override fun canWrite(path: String): Boolean = File(path).canWrite()
    override fun execute(command: Array<String>): Process = Runtime.getRuntime().exec(command)
}

object RootDetection {
    var fileSystem: FileSystem = RealFileSystem()

    fun isRooted(context: Context): Boolean {
        val checks = listOf<Boolean>(
            checkRootBinaries(),
            checkRootPaths(),
            checkBuildTags(Build.TAGS),
            checkDangerousPackages(context),
            checkForRwPaths()
        )
        
        return checks.any { it }
    }
    
    private fun checkRootBinaries(): Boolean {
        val binaries = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        
        for (binary in binaries) {
            if (fileSystem.exists(binary)) {
                return true
            }
        }
        
        return false
    }
    
    private fun checkRootPaths(): Boolean {
        val paths = listOf(
            "/system/xbin/which",
            "/system/bin/which",
            "/system/xbin/ls",
            "/system/bin/ls"
        )
        
        for (path in paths) {
            if (fileSystem.exists(path)) {
                try {
                    val process = fileSystem.execute(arrayOf(path, "su"))
                    val input = process.inputStream.bufferedReader()
                    if (input.readLine() != null) {
                        return true
                    }
                } catch (e: Exception) {
                    // Ignore execution errors
                }
            }
        }
        
        return false
    }
    
    internal fun checkBuildTags(tags: String?): Boolean {
        return tags?.contains("test-keys") == true
    }
    
    private fun checkDangerousPackages(context: Context): Boolean {
        val packages = listOf(
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot",
            "com.amphoras.hidemyrootadfree",
            "com.formyhm.hiderootPremium",
            "com.formyhm.hideroot",
            "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate",
            "com.chelpus.luckypatcher",
            "com.chelpus.lackypatch",
            "com.dimonvideo.luckypatcher"
        )
        
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                // Package not found, continue
            }
        }
        
        return false
    }
    
    private fun checkForRwPaths(): Boolean {
        val paths = listOf("/system", "/system/bin", "/system/xbin", "/sbin", "/vendor/bin")
        for (path in paths) {
            if (fileSystem.canWrite(path)) {
                return true
            }
        }
        return false
    }
    
    fun getDetectionDetails(context: Context): String {
        val details = mutableListOf<String>()
        
        if (checkRootBinaries()) details.add("Root binaries found")
        if (checkRootPaths()) details.add("Root paths detected")
        if (checkBuildTags(Build.TAGS)) details.add("Test keys detected")
        if (checkDangerousPackages(context)) details.add("Dangerous packages found")
        if (checkForRwPaths()) details.add("Writable system partition")
        
        return if (details.isEmpty()) "Device not rooted" else details.joinToString(", ")
    }
}
