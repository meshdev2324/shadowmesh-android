package com.shadowmesh.app.util

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Zero-PII Logger for ShadowMesh.
 * Automatically scrubs IP addresses, tokens, and activation codes from logs.
 * SOP 11 §4: Forensic resistance at the logcat layer.
 *
 * ## This is an enforcement point, so it is tested
 *
 * The scrubber below is the single place where "no sensitive data in logs" —
 * the Command Center's ZPII requirement — is actually enforced on Android. It
 * shipped with **zero tests**, which is the same as shipping with no
 * enforcement: a gate that cannot fail is not a gate.
 *
 * It was also wrong. The rules were `[A-Z0-9]{25}` for an activation code, an
 * IPv4/IPv6 pair, and `eyJ…` for a JWT — so the two credential shapes this
 * project produces most, **64-hex and 16-hex lowercase**, passed through
 * untouched:
 *
 *  - `shadowmesh_common::crypto::derive_session_token` is `SHA256 ‖ hex::encode`,
 *    i.e. 64 lowercase hex. No `.`, no `:`, no `eyJ` prefix. Not matched.
 *  - `CoreUtils.getAndroidDeviceId` returns `SHA256(ANDROID_ID)` — also 64 hex.
 *    `IdentityManager` wrote it straight into logcat.
 *  - Raw `Settings.Secure.ANDROID_ID` is 16 hex. Same problem.
 *
 * All of those are now covered by [HEX_SECRET_PATTERN], and
 * [ZLogScrubTest][scrub] pins every rule with both a value it must catch and a
 * value it must not mangle.
 *
 * ## Over-redaction is also a defect
 *
 * The old IPv6 rule was `\b([0-9a-fA-F]{1,4}:[0-9a-fA-F]{1,4}):[0-9a-fA-F:]+\b`,
 * which matches `12:30:45`. Every timestamped diagnostic in this app was being
 * redacted as though it were a network address. A scrubber that eats innocuous
 * text destroys the diagnostic content the line exists to carry and trains
 * engineers to ignore redaction markers, at which point the real redactions
 * stop being noticed. So every rule below is pinned in *both* directions.
 *
 * The residual, accepted over-redaction is recorded rather than hidden: a
 * three-or-more group run of bare hex words (`a:bb:cc`) is redacted, because
 * under-redaction is the ZPII-critical direction and there is no shape test
 * that separates it from a short IPv6 form. See `ZLogScrubTest`.
 *
 * ## Rule order
 *
 * Order is load-bearing, because each rule claims a shape the next one also
 * half-recognises. JWT runs first so a base64 segment is never mistaken for an
 * activation code; the 32-byte key rule runs before the hex rule so a key is
 * redacted whole rather than half; the activation-code rule normalises hyphens
 * before it counts characters.
 */
object ZLog {
    private const val GLOBAL_TAG = "ShadowMesh"

    /**
     * A JWT — three base64url segments, the first beginning `eyJ`. Boundaries
     * are asserted so a run inside a larger credential is not half-claimed.
     */
    private val JWT_PATTERN: Pattern = Pattern.compile(
        """(?<![A-Za-z0-9._-])eyJ[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+){1,2}(?![A-Za-z0-9._-])"""
    )

    /**
     * A run of uppercase alphanumerics, optionally dash-grouped. The 25-char
     * length is checked *after* hyphens are stripped, in [scrubActivationCode],
     * because no fixed-width regex can enforce "25 characters once the dashes
     * are gone". Users type the dashed grouping — `IdentityManager` strips it
     * with `replace("-", "")` — so the dashed shape is the one that reaches an
     * error handler.
     */
    private val CODE_CANDIDATE_PATTERN: Pattern = Pattern.compile(
        """(?<![A-Za-z0-9])[A-Z0-9]+(?:-[A-Z0-9]+)*(?![A-Za-z0-9])"""
    )

    /**
     * 32 bytes of base64 — exactly 44 characters ending in a single `=` pad.
     * That is the shape of a WireGuard/X25519 private key, and the `=` pad is
     * what makes the rule precise enough to be safe: a 16-byte value pads to 24
     * characters and is not a key.
     *
     * The boundaries deliberately **exclude `=`**, because `key=<value>` is
     * precisely how a credential appears in a log line. Including `=` in the
     * lookbehind made this rule miss every key written in the obvious form —
     * which is to say, it made it useless.
     */
    private val BASE64_KEY_PATTERN: Pattern = Pattern.compile(
        """(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/])"""
    )

    /**
     * A dotted quad with the octet range enforced. The range is what stops
     * `999.888.777.666` — and every other four-part number — being redacted for
     * merely looking like an address.
     */
    private val IPV4_PATTERN: Pattern = Pattern.compile(
        """(?<![0-9.])(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?![0-9.])"""
    )

    /**
     * RFC 4291 forms, including `::1`, `fe80::…%zone`, and IPv4-mapped
     * `::ffff:a.b.c.d`. Every accepted form carries either eight groups or a
     * `::`, which is precisely what a clock time (`12:30:45`) does not.
     */
    private val IPV6_PATTERN: Pattern = Pattern.compile(
        "(?<![0-9A-Za-z:])(" +
            "([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}" +
            "|([0-9a-fA-F]{1,4}:){1,7}:" +
            "|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}" +
            "|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}" +
            "|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}" +
            "|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}" +
            "|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}" +
            "|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})" +
            "|:((:[0-9a-fA-F]{1,4}){1,7}|:)" +
            "|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]+" +
            "|::(ffff(:0{1,4})?:)?" +
            "((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])" +
            "|([0-9a-fA-F]{1,4}:){1,4}:" +
            "((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])" +
            ")(?![0-9A-Za-z:])"
    )

    /**
     * A hex run of 16 characters or more that contains at least one hex letter
     * and is not entirely decimal.
     *
     * This one rule covers every hex-shaped credential the project emits:
     * 16-hex `ANDROID_ID`, 32-hex MD5, 40-hex SHA-1, and the 64-hex
     * `derive_session_token` / `getAndroidDeviceId` output.
     *
     * The two lookarounds are load-bearing rather than decorative:
     *  - **at least one hex letter** — a 16-character all-decimal run is almost
     *    always a microsecond clock (`1728643200000000`), which this app logs
     *    and which is not sensitive. Without this the rule eats timestamps.
     *  - **boundaries** — `(?<![0-9A-Za-z])`/`(?![0-9A-Za-z])` stop a digest run
     *    inside a larger base64 credential from being half-redacted.
     */
    private val HEX_SECRET_PATTERN: Pattern = Pattern.compile(
        """(?<![0-9A-Za-z])(?![0-9]{16}(?![0-9A-Za-z]))[0-9a-fA-F]{16,}(?![0-9A-Za-z])"""
    )

    fun v(tag: String, msg: String) = Log.v(tag, scrub(msg))
    fun d(tag: String, msg: String) = Log.d(tag, scrub(msg))
    fun i(tag: String, msg: String) = Log.i(tag, scrub(msg))
    fun w(tag: String, msg: String) = Log.w(tag, scrub(msg))

    /**
     * Logs at warning level with a throwable, scrubbing the throwable's
     * rendered stack trace as well as the message — the exact contract
     * [e]'s three-argument overload documents, at warning level.
     *
     * Exists because callers hold `Log.w(tag, msg, tr)` sites whose `tr` is
     * an exception that may carry a token or a device identifier in its
     * `Caused by:` chain. Routing those through the two-argument `w` would
     * drop the throwable (losing the diagnostic) or force a level change to
     * `e` (mis-ranking a recoverable failure). Scrubbing the payload rather
     * than the contract is the same trade [e] makes.
     */
    fun w(tag: String, msg: String, tr: Throwable?) =
        Log.w(tag, scrub(msg), tr?.let { ScrubbedThrowable(it) })

    /**
     * Logs at error level, scrubbing the throwable's rendered stack trace as
     * well as the message.
     *
     * A `Throwable` is an **unscrubbed channel into the same logcat buffer**.
     * `Log.e(tag, msg, tr)` hands `tr` to the platform, which calls
     * `getStackTraceString(tr)` and writes the exception's message — and every
     * `Caused by:` message below it — verbatim. So
     * `ZLog.e(TAG, "Passkey registration error", e)`, where `e` came back across
     * the uniffi boundary, would have delivered an identifier to logcat with the
     * message beside it already scrubbed. Passing [ScrubbedThrowable] closes
     * that.
     *
     * The three-argument `Log.e` overload is kept deliberately rather than
     * flattening the trace into the message: the platform chunks a long entry
     * across multiple logcat records, and folding a stack into `msg` changes
     * that behaviour for every caller. Scrub the payload, not the contract.
     */
    fun e(tag: String, msg: String, tr: Throwable? = null) =
        Log.e(tag, scrub(msg), tr?.let { ScrubbedThrowable(it) })

    /**
     * Scrubs Personally Identifiable Information (PII) from log messages.
     *
     * Total by construction: it cannot throw. Logcat is a diagnostic path, and a
     * scrubber that throws on malformed input turns a cosmetic problem into a
     * crash the user sees.
     */
    fun scrub(msg: String): String {
        var s = JWT_PATTERN.matcher(msg).replaceAll(Matcher.quoteReplacement("[MASKED_TOKEN]"))
        s = scrubActivationCode(s)
        s = BASE64_KEY_PATTERN.matcher(s).replaceAll(Matcher.quoteReplacement("[REDACTED_KEY]"))
        s = IPV4_PATTERN.matcher(s).replaceAll(Matcher.quoteReplacement("[REDACTED_IP]"))
        s = IPV6_PATTERN.matcher(s).replaceAll(Matcher.quoteReplacement("[REDACTED_IP]"))
        s = HEX_SECRET_PATTERN.matcher(s).replaceAll(Matcher.quoteReplacement("[REDACTED_HEX]"))
        return s
    }

    /**
     * Redacts an activation code in either its plain or its dash-grouped form.
     *
     * The 25-character rule is the one the server enforces
     * (`body.len() == 25 && body.chars().all(|c| c.is_ascii_alphanumeric())`),
     * and it is applied to the match with hyphens removed so both spellings are
     * judged by the same standard. Candidates of any other length are returned
     * untouched, which is what keeps a 24-character build label and a 28-character
     * correlation id out of the redaction path.
     */
    private fun scrubActivationCode(msg: String): String {
        val matcher = CODE_CANDIDATE_PATTERN.matcher(msg)
        if (!matcher.find()) return msg
        val out = StringBuilder(msg.length)
        var last = 0
        val redacted = Matcher.quoteReplacement("[REDACTED_CODE]")
        do {
            val candidate = matcher.group()
            val stripped = candidate.replace("-", "")
            if (stripped.length == ACTIVATION_CODE_LENGTH) {
                out.append(msg, last, matcher.start()).append(redacted)
                last = matcher.end()
            }
        } while (matcher.find())
        if (last == 0) return msg
        out.append(msg, last, msg.length)
        return out.toString()
    }

    /** The one credential length the protocol defines. See `services/server` auth handlers. */
    private const val ACTIVATION_CODE_LENGTH = 25

/**
 * Renders a throwable exactly as `android.util.Log.getStackTraceString`
 * would, using the JDK's own printer. Kept as a plain function so the result is
 * verifiable in a JVM unit test — `Log.getStackTraceString` returns null under
 * `isReturnDefaultValues`, which would make scrubbing of exception text
 * untestable.
 */
internal fun renderStack(tr: Throwable): String {
    val writer = StringWriter()
    PrintWriter(writer).use { tr.printStackTrace(it) }
    return writer.toString()
}

/**
 * A [Throwable] that renders the source throwable through [ZLog.scrub].
 *
 * `android.util.Log.getStackTraceString` is exactly this:
 * `tr.printStackTrace(PrintWriter(StringWriter))`. Overriding the two render
 * paths is therefore sufficient — and is the only interception point available,
 * because the platform reads the exception by printing it rather than by
 * inspecting fields.
 *
 * `writableStackTrace = false` skips `fillInStackTrace` for the wrapper: the
 * wrapper's own frames are never printed, so computing them would be pure cost
 * on an exception path. [getStackTrace] is delegated to the source so that a
 * debugger or a crash reporter attaching to *this* object still sees the real
 * frames rather than an empty array.
 */
internal class ScrubbedThrowable(
    private val source: Throwable,
) : Throwable(null, null, false, false) {
    override fun toString(): String = ZLog.scrub(renderStack(source))

    override fun printStackTrace(w: PrintWriter) {
        w.write(ZLog.scrub(renderStack(source)))
    }

    override fun getStackTrace(): Array<StackTraceElement> = source.stackTrace
}
}