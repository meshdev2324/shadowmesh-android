# 🤖 ShadowMesh Android Agent Guide

## 🏗 Architectural Blueprint
- **Multi-Module Structure**:
    - `:app`: Navigation, high-level business logic, and UI screens.
    - `:core-vpn`: Encapsulated Rust UniFFI bindings, JNI logic, and `VpnRepository`.
    - `:ui-kit`: Standardized design system, theme, and reusable Compose components.
- **UI**: Jetpack Compose with Material 3 Expressive.
- **State**: `ViewModel` + `StateFlow`. Uses `SavedStateHandle` for process death recovery.
- **Dependency Injection**: **Hilt** is the primary DI framework.
- **Core Bridge**: Decoupled via `VpnRepository` in `:core-vpn`.

## 🔐 Security Logic
- **VPN Service**: `MeshVpnService` (in `:app`) using `foregroundServiceType="specialUse"` for protocol obfuscation.
- **Verification Loop**: Background tunnel health check via external IP verification (SOP 11).
- **Integrity**: `AppIntegrityManager` performs APK signature verification using native layer checks.
- **Anti-Debugging**: Runtime debugger detection enabled.

## 🔄 Anti-Duplication
- **NEVER** write cryptographic or protocol logic in Kotlin. Use the provided bindings in `:core-vpn`.
- Reusable UI elements (Buttons, Cards, Dialogs) MUST be sourced from `:ui-kit`.

## 📐 Interaction Design
- **8dp Grid**: Strictly enforced in `:ui-kit`.
- **Haptic Feedback**: Required for core state transitions (e.g., Connect Button).
- **Human-Centric Copy**: Use reassuring terminology ("You are Protected") instead of technical status.
