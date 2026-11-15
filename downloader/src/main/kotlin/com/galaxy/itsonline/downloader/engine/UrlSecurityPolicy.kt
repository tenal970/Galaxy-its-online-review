package com.galaxy.itsonline.downloader.engine

import java.net.InetAddress
import java.net.URI

/**
 * Pluggable security policy for validating download URLs and resolved IP destinations.
 * Protects against SSRF, unauthorized internal network egress, and dangerous protocol schemes.
 */
interface UrlSecurityPolicy {
    /**
     * Validates URL syntax and scheme.
     * Throws SecurityException if the scheme is not allowed or syntax is malformed.
     */
    @Throws(SecurityException::class)
    fun validateUrl(url: String): URI

    /**
     * Validates whether connection to a resolved IP address is permitted.
     * Throws SecurityException if the IP address belongs to a prohibited subnet
     * (e.g. loopback, private RFC 1918, link-local RFC 3927, Carrier-Grade NAT RFC 6598).
     */
    @Throws(SecurityException::class)
    fun validateAddress(address: InetAddress)
}

/**
 * Strict production-grade URL security policy.
 * - Schemes allowed: Only HTTP and HTTPS.
 * - Subnets forbidden: Loopback, private (RFC 1918), link-local, multicast, wildcard, Carrier-Grade NAT (RFC 6598).
 */
object DefaultUrlSecurityPolicy : UrlSecurityPolicy {

    override fun validateUrl(url: String): URI {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw SecurityException("Invalid URL syntax: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw SecurityException("Unsupported URL scheme '${uri.scheme}'. Only HTTP and HTTPS are permitted.")
        }

        if (uri.host.isNullOrBlank()) {
            throw SecurityException("URL host is missing")
        }

        return uri
    }

    override fun validateAddress(address: InetAddress) {
        if (address.isLoopbackAddress) {
            throw SecurityException("Access to loopback address (${address.hostAddress}) is blocked for security")
        }
        if (address.isSiteLocalAddress) {
            throw SecurityException("Access to private network address (${address.hostAddress}) is blocked for security")
        }
        if (address.isLinkLocalAddress) {
            throw SecurityException("Access to link-local address (${address.hostAddress}) is blocked for security")
        }
        if (address.isAnyLocalAddress) {
            throw SecurityException("Access to wildcard local address (${address.hostAddress}) is blocked for security")
        }
        if (address.isMulticastAddress) {
            throw SecurityException("Access to multicast address (${address.hostAddress}) is blocked for security")
        }

        val raw = address.address
        if (raw.size == 4) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            // 100.64.0.0/10 (Carrier-Grade NAT, RFC 6598)
            if (b0 == 100 && (b1 and 0xC0) == 64) {
                throw SecurityException("Access to Carrier-Grade NAT address (${address.hostAddress}) is blocked for security")
            }
            // 0.0.0.0/8
            if (b0 == 0) {
                throw SecurityException("Access to this-network address (${address.hostAddress}) is blocked for security")
            }
        }
    }
}
