use serde::{Deserialize, Serialize};

/// Level of Quantum Resistance applied to the transport.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum QuantumResistanceLevel {
    /// No post-quantum protection.
    #[serde(rename = "NONE")]
    NONE,
    /// Hybrid X25519 + ML-KEM (Kyber768) protection.
    #[serde(rename = "HYBRID")]
    HYBRID,
    /// Pure Post-Quantum (Experimental).
    #[serde(rename = "FULL")]
    FULL,
}

/// Persistent user settings for the ShadowMesh client.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserSettings {
    /// Desired quantum resistance level.
    pub quantum_level: QuantumResistanceLevel,
    /// Whether the system-wide kill switch is enabled.
    pub kill_switch_enabled: bool,
    /// Whether DNS leak protection is active.
    pub dns_leak_protection: bool,
    /// Whether emergency network recovery is permitted.
    pub emergency_recovery_enabled: bool,
    /// A list of preferred DNS server addresses.
    pub dns_servers: Vec<String>,
}

impl Default for UserSettings {
    fn default() -> Self {
        Self {
            quantum_level: QuantumResistanceLevel::NONE,
            kill_switch_enabled: false,
            dns_leak_protection: true,
            emergency_recovery_enabled: true,
            dns_servers: vec!["10.8.0.1".to_string()],
        }
    }
}
