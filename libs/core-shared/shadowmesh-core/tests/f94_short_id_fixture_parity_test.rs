//! **The fixture-parity gate for the short-id rule.** (RFC-049 §4.4,
//! RULING F98-3b.)
//!
//! The short-id validity rule is expressed in **three** places:
//!
//! | # | Expression | What it decides | Owner |
//! | :--- | :--- | :--- | :--- |
//! | 1 | the **emitter** — `reality::generate_short_id_with_entropy_source` | what the core may **hand out** | **L9** (this crate) |
//! | 2 | the **node's** accept rule — `transport::reality_server::decode_acceptable_short_id` | what the **node** will accept | **L9** (this crate) |
//! | 3 | the **control plane's** policy — `is_matchable_short_id` | what the control plane will **issue** | **L8** (`services/server`) |
//!
//! **This file is the core's half**: expressions 1 and 2, asserted against
//! **one** fixture table (`shadowmesh_core::short_id_rule::SHORT_ID_FIXTURES`).
//! The server's half imports the same table and asserts expression 3 against
//! it — see the report for what L8 must build.
//!
//! # Why a table and not three tests that each assert themselves
//!
//! ⚠️ **Name-pairing is a convention, and a convention cannot fail.** Each
//! copy's test asserts its own copy, so if one copy's range changes, the
//! other copies' tests **still pass**. The concrete failure is not
//! hypothetical (RFC-049 §4.4):
//!
//! > *Widen the core's rule to 1..=16 bytes and the server's copy still
//! > admits 1..=8 ⇒ the control plane hands a client a nine-byte id ⇒ **the
//! > node silently skips it** ⇒ the handshake fails, and neither side can
//! > name the cause.*
//!
//! ⇒ One table, three expressions, one place. **The table is the pin.**
//!
//! # The red-proof this gate found
//!
//! ⚠️ **The emitter did not express the rule at all.** Before this gate, the
//! emitter checked the **length** of the entropy source's output and nothing
//! else — so an entropy source that returned eight zero bytes produced
//! `Ok("0000000000000000")`: **the constant credential the whole rule exists
//! to refuse**, handed out by the one component that is supposed to never
//! produce it.
//!
//! The probability of a real CSPRNG returning that is 2⁻⁶⁴, so this was
//! never observed in the field — but the **injection seam exists precisely so
//! that the failure path is testable**, and a rule that one of its three
//! expressions does not enforce is a rule that three expressions do not share.
//! The node would have refused the id (correctly) and the emitter would have
//! handed it out (wrongly), and the handshake would have failed with no
//! diagnosis on either side.
//!
//! **Reverting the emitter's zero check reproduces the red** — it is the
//! one-line change named in the commit that landed this gate.

use shadowmesh_core::reality::generate_short_id_with_entropy_source;
use shadowmesh_core::short_id_rule::{ShortIdVerdict, SHORT_ID_FIXTURES};
use shadowmesh_core::transport::reality_server::decode_acceptable_short_id;
use shadowmesh_core::ShadowMeshError;

/// **Expression 2 — the node's accept rule.** Every fixture, one verdict.
#[test]
fn the_node_accepts_exactly_the_fixtures_the_table_marks_matchable() {
    for fixture in SHORT_ID_FIXTURES {
        let decoded = decode_acceptable_short_id(fixture.hex);
        let accepted = decoded.is_some();

        assert_eq!(
            accepted,
            fixture.is_matchable(),
            "fixture `{}` (hex {:?}): the node's accept rule says {} and the table says {} — \
             the node's copy of the rule and the table have drifted",
            fixture.name,
            fixture.hex,
            if accepted { "matchable" } else { "refused" },
            if fixture.is_matchable() { "matchable" } else { "refused" },
        );
    }
}

/// **Expression 1 — the emitter.** The emitter must never hand out a value
/// the table refuses.
///
/// ⚠️ **This is the assertion that was red before the gate landed.** An
/// all-zero entropy source produced `Ok("0000000000000000")` — the constant
/// credential — because the emitter checked the length and not the entropy.
#[test]
fn the_emitter_refuses_to_hand_out_a_value_the_table_refuses() {
    for fixture in SHORT_ID_FIXTURES {
        let Some(bytes) = fixture.bytes else {
            // A text that is not hex has no byte form, so the emitter cannot
            // be handed it. The emitter's input is bytes, not text.
            continue;
        };
        if bytes.len() != shadowmesh_core::reality::SHORT_ID_LEN {
            // The emitter always asks for exactly `SHORT_ID_LEN` bytes, so a
            // shorter or longer fixture is not one it can produce.
            continue;
        }

        let emitted = generate_short_id_with_entropy_source(|_len| Some(bytes.to_vec()));

        // The table states the VERDICT, and the emitter must agree with it.
        // A refused fixture must be REFUSED; a matchable one must be handed
        // out — and handed out verbatim, because the emitter hex-encodes what
        // it is given.
        if matches!(fixture.verdict, ShortIdVerdict::Refused) {
            assert!(
                emitted.is_err(),
                "fixture `{}`: the emitter handed out {emitted:?}, which the table REFUSES — \
                 the emitter and the table do not share the rule (RFC-049 §4.4)",
                fixture.name
            );
            continue;
        }

        let emitted = emitted.expect("the emitter must hand out a matchable value");

        assert_eq!(
            emitted, fixture.hex,
            "fixture `{}`: the emitter must emit the configured bytes verbatim",
            fixture.name
        );

        assert!(
            decode_acceptable_short_id(&emitted).is_some(),
            "fixture `{}`: the emitter handed out {:?}, which the NODE REFUSES — the \
             emitter and the node do not share the rule, and a handshake would fail \
             with no diagnosis on either side (RFC-049 §4.4)",
            fixture.name,
            emitted
        );
    }
}

/// **Expression 1, stated directly.** An all-zero entropy source is a
/// refusal, not a credential.
///
/// This is the case the emitter used to get wrong, isolated so that its
/// failure cannot hide behind the table's other rows.
#[test]
fn the_emitter_refuses_an_all_zero_entropy_source() {
    let err = generate_short_id_with_entropy_source(|_len| Some(vec![0u8; 8]))
        .expect_err("an all-zero entropy source must NOT produce a short id");

    assert!(
        matches!(err, ShadowMeshError::KeyGenerationFailed),
        "the refusal must be reported as a key-generation failure, got: {err:?}"
    );
}

/// **The emitter's output is always in the accepted set.**
///
/// The property, stated over the real generator rather than over a fixture:
/// every id the core hands out is one the node accepts.
#[test]
fn every_id_the_emitter_hands_out_is_one_the_node_accepts() {
    for _ in 0..16 {
        let id = generate_short_id_with_entropy_source(|len| {
            // A deterministic, non-degenerate source: the point of this
            // assertion is the **relationship** between the two expressions,
            // not the randomness.
            Some((0..len).map(|index| (index as u8) + 1).collect())
        })
        .expect("the source must produce bytes");

        assert!(
            decode_acceptable_short_id(&id).is_some(),
            "the emitter handed out {id:?} and the node refuses it — the two copies of the \
             rule do not agree (RFC-049 §4.4)"
        );
    }
}

/// **The table is not a list of trivia.** Every fixture must be *used*, and
/// the two bounds must be independently represented.
#[test]
fn the_table_covers_both_bounds_and_the_shape_cases() {
    let names: Vec<&str> = SHORT_ID_FIXTURES.iter().map(|f| f.name).collect();

    for required in [
        "the_empty_id",
        "the_all_zero_id",
        "the_one_byte_id",
        "the_eight_byte_id",
        "the_nine_byte_id",
        "the_non_hex_id",
        "the_mixed_case_id",
        "the_zero_padded_id",
    ] {
        assert!(
            names.contains(&required),
            "the table must carry the `{required}` case — RFC-049 §4.4 names it, and a table \
             that loses a case stops being the pin it was built to be"
        );
    }

    // Both bounds, independently: a case that is the right length with no
    // entropy, and a case that has entropy but is the wrong length.
    assert!(SHORT_ID_FIXTURES.iter().any(|f| f.is_all_zero_bytes()));
    assert!(
        SHORT_ID_FIXTURES
            .iter()
            .any(|f| matches!(f.verdict, ShortIdVerdict::Refused) && !f.is_all_zero_bytes()),
        "the table must carry a refusal that is NOT about the all-zero rule — otherwise the \
         length bound is never independently exercised"
    );
}
