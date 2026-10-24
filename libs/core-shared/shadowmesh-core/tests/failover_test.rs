use shadowmesh_core::get_default_user_settings;
use shadowmesh_core::vpn_manager::{
    ConnectionStatus, TrafficMode, TrafficModePreference, VPNManager,
};

mod common;
use common::mock_node;

/// Failover escalation driven through the public retry_connection() API:
/// Normal → Fragmented → REALITY across attempts, without manual forcing.
#[tokio::test]
async fn test_multi_phase_failover_escalation() {
    let settings = get_default_user_settings();
    let manager = VPNManager::new(settings);
    manager.activate("test-code".into(), None, None, 1, 30).unwrap();

    let node = mock_node();
    manager.set_selected_node(node.clone());

    // Stealth preference escalates: attempt 1 → Fragmented.
    manager.set_traffic_mode_preference(TrafficModePreference::Stealth);
    manager.set_traffic_mode(TrafficMode::Normal);
    manager.initiate_connection(node.clone(), "device-key".into()).unwrap();
    assert_eq!(manager.get_status(), ConnectionStatus::ConnectingFragmented);

    // Retry escalates the attempt counter: attempt 2 → REALITY.
    manager.retry_connection(node.clone(), "device-key".into()).unwrap();
    assert_eq!(manager.get_status(), ConnectionStatus::ConnectingReality);

    // complete resets the counter; a fresh cycle starts at Fragmented again.
    manager.complete_connection();
    manager.disconnect();
    manager.initiate_connection(node, "device-key".into()).unwrap();
    assert_eq!(manager.get_status(), ConnectionStatus::ConnectingFragmented);
}

/// Escalation must also honor a forced traffic mode (the "current mode !=
/// Normal" branch keeps the operator's explicit choice).
#[tokio::test]
async fn test_failover_mode_escalation() {
    let settings = get_default_user_settings();
    let manager = VPNManager::new(settings);
    manager.activate("test-code".into(), None, None, 1, 30).unwrap();

    let node = mock_node();
    manager.set_selected_node(node.clone());

    // Phase 1: DPI-evasion escalation starts from Fragmented.
    manager.set_traffic_mode(TrafficMode::Fragmented);
    manager.initiate_connection(node.clone(), "device-key".into()).unwrap();
    assert_eq!(manager.get_status(), ConnectionStatus::ConnectingFragmented);

    manager.disconnect();

    // Phase 2: highest escalation tier is REALITY.
    manager.set_traffic_mode(TrafficMode::Reality);
    manager.initiate_connection(node, "device-key".into()).unwrap();
    assert_eq!(manager.get_status(), ConnectionStatus::ConnectingReality);
}

/// Stealth preference must escalate Normal → Fragmented on the first attempt
/// without any explicit traffic-mode forcing.
#[tokio::test]
async fn test_stealth_preference_escalation() {
    let settings = get_default_user_settings();
    let manager = VPNManager::new(settings);
    manager.activate("test-code".into(), None, None, 1, 30).unwrap();
    manager.set_traffic_mode_preference(TrafficModePreference::Stealth);
    manager.set_traffic_mode(TrafficMode::Normal);

    let node = mock_node();
    manager.set_selected_node(node.clone());

    // First attempt under Stealth escalates straight to Fragmented.
    manager.initiate_connection(node, "device-key".into()).unwrap();
    assert_eq!(manager.get_status(), ConnectionStatus::ConnectingFragmented);
}
