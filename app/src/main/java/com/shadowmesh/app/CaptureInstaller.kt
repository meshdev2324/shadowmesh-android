package com.shadowmesh.app

import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * The one seam through which the capture is installed.
 *
 * RFC-048 §4.3: *"the act of installing it is behind one injectable seam"*, and
 * the seam's own signature is
 * `CaptureInstaller(spec) -> ParcelFileDescriptor?` — *"the ONLY call to
 * establish()"* in the tree.
 *
 * The handle is a type parameter so that the JVM tests can drive the seam
 * without an Android `ParcelFileDescriptor`, which cannot be constructed
 * off-device: `ParcelFileDescriptor.fromFd` and `adoptFd` are stubs in a plain
 * unit test and throw. Production uses `ParcelFileDescriptor`; the tests use a
 * stand-in. The *ordering* is what the seam exists to expose, and the ordering
 * does not care what the handle is made of.
 */
fun interface CaptureInstaller<H> {
    /**
     * Installs [spec] and returns the established handle, or null if the
     * platform refused the capture.
     *
     * A null return is not a failure of the transport — it is the platform
     * saying "no VPN consent, or another app took the slot".
     */
    fun install(spec: TunnelCaptureSpec): H?
}

/**
 * The production installer: the only place in the tree that calls
 * `VpnService.Builder.establish()`.
 *
 * It applies the specification to the builder and establishes. The builder
 * itself carries the settings that are not part of the capture — the MTU, the
 * non-blocking hint, the session name, the IPv6 posture and the split-tunnel
 * rules — because those are not "what the capture is", they are "how this
 * device runs the capture".
 *
 * The order of the builder calls does not matter: the builder is a
 * configuration bag, and the only ordering that matters is that every rule is
 * applied **before** [establish].
 */
class VpnServiceCaptureInstaller(
    private val builder: VpnService.Builder,
) : CaptureInstaller<ParcelFileDescriptor> {
    override fun install(spec: TunnelCaptureSpec): ParcelFileDescriptor? {
        builder.addAddress(spec.address, spec.prefixLength)
        spec.route?.let { builder.addRoute(it, spec.routePrefixLength) }
        spec.resolver?.let { builder.addDnsServer(it) }
        return builder.establish()
    }
}
