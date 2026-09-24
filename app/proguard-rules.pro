# uniffi-shadowmesh bindings
-keep class uniffi.shadowmesh.** { *; }
-keep interface uniffi.shadowmesh.** { *; }

# ShadowMesh Core Models
-keep class com.shadowmesh.core_vpn.domain.** { *; }
-keep class com.shadowmesh.core_vpn.Config { *; }

# Prevent stripping of JNI methods
-keepclasseswithmembers class * {
    native <methods>;
}

# JNA Proguard Rules
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# WireGuard
-keep class com.wireguard.android.** { *; }

# Kotlin Serialization
-keepclassmembers class * {
    @org.jetbrains.kotlinx.serialization.SerialName <fields>;
}

# Material Icons - Keep only used icons
-keep class androidx.compose.material.icons.Icons** { *; }

# ML Kit Barcode Scanning
# These rules prevent R8 from stripping internal providers and creators
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**


