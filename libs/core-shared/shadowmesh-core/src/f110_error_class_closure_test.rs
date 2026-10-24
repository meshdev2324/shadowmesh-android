//! F110 — the core's **error half** of the raw-server-string rule.
//!
//! L10's roster-disclosure ruling (`docs/SECURITY_SCAN_2026-10-21-L10-roster-disclosure-ruling.md`,
//! §7.2 / **M-2**): *"no server-authored string reaches a client surface.
//! The floor: a server string may be **inspected** to classify a failure; it
//! may never be **carried** into a render, notification, export — or a log.
//! Classify on the TYPE, never the STRING."*
//!
//! L7 closed the **client half** (`apps/desktop-native/src/connection_failure.rs`:
//! the closed-set classes, and the source gate over `main.rs`). This file is
//! the **core half**: the core is the *source* of the strings that cross
//! `[Throws=ShadowMeshError]`, so the closure is enforced **here**, at the
//! raise.
//!
//! The leak chain the first RED proved (the RED run is recorded in the
//! commit that lands this file and in the F110 report; executed, not
//! derived):
//!
//! ```text
//! server body { "message": "Session expired. …" }        (the control plane's own sentence)
//!   → ApiErrorResponse.message                            (api_client.rs — the parse)
//!   → ShadowMeshError::Unauthorized(err_body.message)     (api_client.rs — the raise)
//!   → format!("Authentication failed: {s}")               (lib.rs — ShadowMeshError's Display)
//!   → daemon  VpnResponse.message  "API Error: {e}"       (daemon.rs — the carriage)
//!   → a client surface                                    (iOS renders it verbatim — L10 §7.2)
//! ```
//!
//! ⇒ **The closure is taken at the raise**: a control-plane-derived failure
//! is classified on the **type** (the HTTP status — and nothing else), and
//! the message string carries only the class's **own wording**
//! (`ShadowMeshError::as_str()`), never the control plane's sentence.
//!
//! **Where the client half's parity lives:** the class names are the
//! **variant discriminants** of `ShadowMeshError` — they do not drift, and
//! no fourth spelling of their wording exists anywhere in the tree. L7's
//! desktop classes (`connection_failure.rs`) are the *client surfaces'* own
//! labels behind those same discriminants: `Refused` is the control-plane
//! refusal family (`Unauthorized`, `Forbidden`, `SessionFrozen`),
//! `DaemonUnreachable`/`NoServers` are facts the client owns, and `Core` is
//! everything else the core raises. One vocabulary, two display layers
//! (L10 §5), each spelled once.

use crate::ShadowMeshError;

/// The hostile control plane, speaking through its own error body.
///
/// Deliberately the worst case: the **account vocabulary** L10 bans
/// (`session`, `expired`, `deactivation`) plus a fabricated surveillance
/// claim — precisely the sentence a malicious or compromised control plane
/// would like to print on a client screen.
pub(crate) const HOSTILE_SERVER_SENTENCE: &str =
    "Session expired. Your device is fingerprinted as a known dissident. Submit for deactivation.";

/// The sentence's distinctive fragments, split so a punctuation tweak in the
/// surface plumbing cannot smuggle the leak past the gate.
pub(crate) const HOSTILE_SENTENCE_FRAGMENTS: [&str; 5] =
    ["Session expired", "fingerprinted", "known dissident", "deactivation", "deactivate"];

/// Nothing of a hostile control-plane sentence may reach the error's
/// message string.
///
/// ⚠️ **RED on the pre-fix tree**: the old `Display` interpolated the
/// payload — e.g. `format!("Authentication failed: {}", s)` — so the hostile
/// sentence appeared in `to_string()`, and this test failed with the
/// sentence printed back in its own assertion message.
pub(crate) fn assert_message_string_carries_no_control_plane_sentence(
    err: ShadowMeshError,
    site: &str,
) {
    let message = err.to_string();
    for fragment in HOSTILE_SENTENCE_FRAGMENTS {
        assert!(
            !message.contains(fragment),
            "F110 ({site}): a control-plane sentence fragment reached the error's \
             message string (`\"{fragment}\"` inside `{message}`) — a server-authored \
             string may be inspected to classify a failure, never carried into one \
             that is surfaced (L10 §7.2 / M-2; classify on the TYPE)."
        );
    }
}

/// A control-plane sentence cannot hide in the classes raised by the
/// **activation** refusal — `desktop_controller` raised
/// `Unauthorized(response.message)` off the same server field, and the
/// exported boundary hands whatever caller the variant's payload. Only the
/// class's own wording may ride the surfaced message.
#[test]
fn the_activation_refusal_carries_no_control_plane_sentence() {
    let refused = ShadowMeshError::Unauthorized(HOSTILE_SERVER_SENTENCE.to_string());
    assert_message_string_carries_no_control_plane_sentence(refused, "activation ctor");
}

/// The PoW challenge nonce is **protocol material** — the tunnel-path solver
/// needs it, and the daemon is its only consumer — but it is still a
/// server-authored string, so it is never rendered: the message string
/// spells the class wording and nothing else.
#[test]
fn the_pow_challenge_is_protocol_material_and_never_reaches_the_message_string() {
    let friction = ShadowMeshError::AdaptiveFrictionRequired(HOSTILE_SERVER_SENTENCE.to_string());
    assert_message_string_carries_no_control_plane_sentence(friction, "pow challenge");
}

/// The `Forbidden` refusal: the payload is resolutely dropped — the class's
/// own wording carries.
#[test]
fn the_forbidden_refusal_carries_no_control_plane_sentence() {
    let forbidden = ShadowMeshError::Forbidden(HOSTILE_SERVER_SENTENCE.to_string());
    assert_message_string_carries_no_control_plane_sentence(forbidden, "forbidden ctor");
}

/// And the refusal cannot arrive on a payloadless class either: the positive
/// half of the closure — the class exists, gives a message, and carries
/// nothing of the control plane's.
#[test]
fn the_frozen_class_carries_nothing_at_all() {
    let frozen = ShadowMeshError::SessionFrozen;
    assert_message_string_carries_no_control_plane_sentence(frozen, "frozen class");
}

// ── The vocabulary/parity half (GREEN phase — needs `as_str`) ──────────────
//
// RED/GREEN declaration, honestly: the four tests above were the executed
// RED (asserted against the pre-fix `Display`, which interpolated the
// payload — recorded in the report as exit code 100 with the hostile
// sentence visible inside the failed assertion's message). The three tests
// below were written WITH the fix, because they assert the vocabulary table
// (`as_str`/`sealed`) that the fix introduces; their "red" is only the
// compile error of a missing `as_str` on the pre-fix tree, which is why
// they are not claimed as an executed red.

/// Constructs one error per variant — the payload variants get a probe
/// payload that is core-authored text, not a control-plane sentence — so a
/// wording assertion can be made per CLASS.
fn one_error_per_class() -> Vec<ShadowMeshError> {
    vec![
        ShadowMeshError::AdaptiveFrictionRequired("a1b2c3d4e5f60718".to_string()),
        ShadowMeshError::KeyGenerationFailed,
        ShadowMeshError::PowTimeout,
        ShadowMeshError::ConnectionFailed,
        ShadowMeshError::Unauthorized("probe-payload".to_string()),
        ShadowMeshError::Forbidden("probe-payload".to_string()),
        ShadowMeshError::SessionFrozen,
        ShadowMeshError::NotFound("probe-payload".to_string()),
        ShadowMeshError::TooManyRequests("probe-payload".to_string()),
        ShadowMeshError::NodeNotFound,
        ShadowMeshError::IoError("probe-payload".to_string()),
        ShadowMeshError::JsonError("probe-payload".to_string()),
        ShadowMeshError::InvalidDuration,
        ShadowMeshError::ServerError("probe-payload".to_string()),
        ShadowMeshError::SocketTuningFailed,
        ShadowMeshError::DnsResolutionError,
        ShadowMeshError::HeaderMagicMismatch,
        ShadowMeshError::UnsupportedProtocolVersion,
        ShadowMeshError::InvalidAddressType,
        ShadowMeshError::AuthTagVerificationFailed,
        ShadowMeshError::InvalidStateTransition,
        ShadowMeshError::Other("probe-payload".to_string()),
        ShadowMeshError::Common("probe-payload".to_string()),
    ]
}

/// The vocabulary is **bijective**: every class has a wording, and no two
/// classes share one — a reader (or a classifying client) can tell two
/// classes apart from the message string alone (the same bijection rule
/// L6/L10 set for the roster states, applied to the core's own class set).
#[test]
fn every_core_class_has_its_own_canonical_wording() {
    let errors: Vec<ShadowMeshError> = one_error_per_class();
    assert_eq!(errors.len(), 23, "the class set this test walks changes with the enum");
    let wordings: Vec<&str> = errors.iter().map(|e| e.as_str()).collect();
    for wording in &wordings {
        assert!(!wording.is_empty(), "a class has an empty canonical wording");
    }
    let mut unique = wordings.clone();
    unique.sort_unstable();
    unique.dedup();
    assert_eq!(
        unique.len(),
        wordings.len(),
        "two classes share a canonical wording — the message string can no longer \
         tell the class set apart"
    );
}

/// The message string is spelled from the ONE wording table:
///
/// * a **control-plane class** renders `as_str()` exactly — its payload is
///   never rendered (the closure the four RED-phase tests enforce);
/// * a **local-diagnostic class** renders its own local payload after the
///   class wording (`IO error: connection refused`), because that payload
///   is the OS's/parser's/core's own text by construction;
/// * the payloadless classes render `as_str()` guarded by the exact-match
///   heritage assertions this suite already carries.
#[test]
fn the_message_string_is_spelled_from_the_wording_table() {
    let errors: Vec<ShadowMeshError> = one_error_per_class();

    for err in errors {
        let wording = err.as_str();
        let message = err.to_string();
        assert!(
            message.contains(wording),
            "the message string must be spelled from the wording table: `{wording}` \
             missing from `{message}`"
        );
        match &err {
            ShadowMeshError::AdaptiveFrictionRequired(_)
            | ShadowMeshError::Unauthorized(_)
            | ShadowMeshError::Forbidden(_)
            | ShadowMeshError::NotFound(_)
            | ShadowMeshError::TooManyRequests(_)
            | ShadowMeshError::ServerError(_) => {
                assert_eq!(
                    message, wording,
                    "a control-plane class renders its canonical wording and nothing \
                     else — its payload is never rendered (F110)"
                );
            }
            ShadowMeshError::IoError(s)
            | ShadowMeshError::JsonError(s)
            | ShadowMeshError::Other(s)
            | ShadowMeshError::Common(s) => {
                assert_eq!(
                    message,
                    format!("{}: {}", wording, s),
                    "a local-diagnostic class carries its OWN local payload after the \
                     class wording — these payloads are OS/parser/core text by \
                     construction, and a change that hands them a control-plane \
                     sentence is a raise-site regression, held off by the closure tests"
                );
            }
            _ => {}
        }
    }
}

/// The **sealed raise** raises in the class's own language: the payload of
/// a sealed control-plane class IS the canonical wording, and the message
/// string carries no sentence — the contract the raise sites depend on.
/// The PoW nonce is the documented exception: it is carried intact (the
/// solver consumes it) and never rendered.
#[test]
fn the_sealed_raise_speaks_only_in_the_class_wording() {
    use ShadowMeshError as E;

    let sealed: Vec<E> = vec![
        E::sealed(E::Unauthorized(HOSTILE_SERVER_SENTENCE.to_string())),
        E::sealed(E::Forbidden(HOSTILE_SERVER_SENTENCE.to_string())),
        E::sealed(E::NotFound(HOSTILE_SERVER_SENTENCE.to_string())),
        E::sealed(E::TooManyRequests(HOSTILE_SERVER_SENTENCE.to_string())),
        E::sealed(E::ServerError(HOSTILE_SERVER_SENTENCE.to_string())),
    ];
    for err in sealed {
        let wording = err.as_str();
        match &err {
            E::Unauthorized(payload)
            | E::Forbidden(payload)
            | E::NotFound(payload)
            | E::TooManyRequests(payload)
            | E::ServerError(payload) => {
                assert_eq!(
                    payload, wording,
                    "a sealed control-plane class carries the class wording as its \
                     payload — never the raising site's sentence"
                );
            }
            other => panic!("sealed({other:?}) must stay in its own class"),
        }
        assert_message_string_carries_no_control_plane_sentence(err, "sealed");
    }

    let nonce = E::sealed(E::AdaptiveFrictionRequired("a1b2c3d4e5f60718".to_string()));
    match &nonce {
        E::AdaptiveFrictionRequired(carried) => {
            assert_eq!(
                carried, "a1b2c3d4e5f60718",
                "the PoW nonce is protocol material: sealed() keeps it intact for the solver"
            );
        }
        other => panic!("sealed() must preserve the friction class; got {other:?}"),
    }
    assert_message_string_carries_no_control_plane_sentence(nonce, "sealed pow");

    let payloadless = E::sealed(E::SessionFrozen);
    assert!(matches!(payloadless, E::SessionFrozen), "a payloadless class seals to itself");
}
