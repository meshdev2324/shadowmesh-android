//! **The short-id rule, in ONE table.**
//!
//! RFC-049 §4.4, **RULING F98-3b**: the short-id validity rule is expressed
//! in three places, the duplication is **accepted** (the three answer
//! different questions), and the duplication is protected by **name-pairing**.
//!
//! ⚠️ **Name-pairing is a convention, and a convention cannot fail.** If one
//! copy's range changes, the other copies' tests still pass. The concrete
//! failure is not hypothetical:
//!
//! > *Widen the core's rule to 1..=16 bytes and the server's copy still
//! > admits 1..=8 ⇒ the control plane hands a client a nine-byte id ⇒ **the
//! > node silently skips it** ⇒ the handshake fails, and neither side can
//! > name the cause.* (RFC-049 §4.4)
//!
//! ⇒ **This table is the pin.** One fixture list, asserted against **every**
//! expression of the rule, in one place. The three expressions are:
//!
//! | # | Expression | What it decides | Owner |
//! | :--- | :--- | :--- | :--- |
//! | 1 | the **emitter** — `reality::generate_short_id_with_entropy_source` | what the core may **hand out** | **L9** (this crate) |
//! | 2 | the **node's** accept rule — `transport::reality_server::decode_acceptable_short_id` | what the **node** will accept | **L9** (this crate) |
//! | 3 | the **control plane's** policy — `is_matchable_short_id` | what the control plane will **issue** to a client | **L8** (`services/server`) |
//!
//! ⚠️ **The table is `pub` so that expression 3 can assert against it.** The
//! server's test imports it (`use shadowmesh_core::short_id_rule::…`), so
//! the three copies assert against **one** list rather than against
//! themselves. That is the whole point: each copy's test passing proves
//! nothing about the other two.

/// What the rule says about one short id.
///
/// The rule, in one line:
///
/// > **a matchable short id is 1..=8 bytes of hex carrying at least one
/// > non-zero byte**
///
/// ⚠️ **The zero PADDING is not the zero CREDENTIAL.** A two-byte short id
/// is compared against an eight-byte slot whose tail is zeros. That is the
/// wire format, not a credential. The rule is about the **configured bytes**,
/// never about the padded slot — and `ZeroPadded` below is the fixture that
/// pins that distinction, because it is the one an over-eager "no zeros
/// anywhere" reading would get wrong.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShortIdVerdict {
    /// The id is a usable credential: every expression of the rule accepts it.
    Matchable,
    /// The id may not be used as a credential: every expression refuses it.
    Refused,
}

/// One fixture: the name, the hex text, the bytes it decodes to, and the
/// verdict the rule gives it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ShortIdFixture {
    /// The fixture's name — the same name in **every** copy's test, so the
    /// three tests are greppable against each other.
    pub name: &'static str,
    /// The short id, as the text a configuration carries.
    pub hex: &'static str,
    /// The bytes the text decodes to, or `None` when the text is not hex at
    /// all.
    pub bytes: Option<&'static [u8]>,
    /// What the rule says about it.
    pub verdict: ShortIdVerdict,
}

impl ShortIdFixture {
    /// Whether the rule accepts this id.
    pub const fn is_matchable(self) -> bool {
        matches!(self.verdict, ShortIdVerdict::Matchable)
    }

    /// Whether this id's bytes are **all zero** — the constant-credential
    /// case, which is the one the emitter must refuse.
    ///
    /// ⚠️ This is about the **configured bytes**, never about the padded
    /// slot: a two-byte id that is `00 00` is all-zero and refused, while a
    /// two-byte id that is `ab cd` is matchable and merely *pads* to zeros.
    ///
    /// ⚠️ **Not a `const fn`**: `slice::iter()` is not const-stable on this
    /// toolchain (rustc 1.98.0), and a rule that only exists inside a
    /// `const fn` is a rule the release build cannot compile.
    pub fn is_all_zero_bytes(self) -> bool {
        match self.bytes {
            Some(bytes) => !bytes.is_empty() && bytes.iter().all(|byte| *byte == 0),
            None => false,
        }
    }
}

/// **THE fixture table.** One list, every expression of the rule.
///
/// The cases are RFC-049 §4.4's: *empty, all-zero, one byte, eight bytes,
/// nine bytes, non-hex, mixed case, zero-padded* — plus **one zero byte**,
/// which is added because it is the case that separates the two bounds: it
/// is the right LENGTH and has no ENTROPY, so a copy that checked only the
/// length would accept it.
pub const SHORT_ID_FIXTURES: [ShortIdFixture; 9] = [
    ShortIdFixture {
        name: "the_empty_id",
        hex: "",
        // The empty text **is** valid hex — it decodes to zero bytes. It is
        // refused for being empty, not for being all-zero, and the two are
        // different bounds.
        bytes: Some(&[]),
        verdict: ShortIdVerdict::Refused,
    },
    ShortIdFixture {
        name: "the_all_zero_id",
        hex: "0000000000000000",
        bytes: Some(&[0u8; 8]),
        verdict: ShortIdVerdict::Refused,
    },
    ShortIdFixture {
        name: "the_one_zero_byte_id",
        hex: "00",
        bytes: Some(&[0u8; 1]),
        verdict: ShortIdVerdict::Refused,
    },
    ShortIdFixture {
        name: "the_one_byte_id",
        hex: "ab",
        bytes: Some(&[0xab]),
        verdict: ShortIdVerdict::Matchable,
    },
    ShortIdFixture {
        name: "the_eight_byte_id",
        hex: "0123456789abcdef",
        bytes: Some(&[0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef]),
        verdict: ShortIdVerdict::Matchable,
    },
    ShortIdFixture {
        name: "the_nine_byte_id",
        hex: "0123456789abcdef00",
        bytes: Some(&[0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x00]),
        verdict: ShortIdVerdict::Refused,
    },
    ShortIdFixture {
        name: "the_non_hex_id",
        hex: "zzzz",
        bytes: None,
        verdict: ShortIdVerdict::Refused,
    },
    ShortIdFixture {
        name: "the_mixed_case_id",
        hex: "aBcD1234",
        bytes: Some(&[0xab, 0xcd, 0x12, 0x34]),
        verdict: ShortIdVerdict::Matchable,
    },
    ShortIdFixture {
        name: "the_zero_padded_id",
        hex: "abcd",
        bytes: Some(&[0xab, 0xcd]),
        verdict: ShortIdVerdict::Matchable,
    },
];
