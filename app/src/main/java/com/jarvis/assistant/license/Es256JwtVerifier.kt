package com.jarvis.assistant.license

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/** Public key from the licence server's JWK set, with its key id when the server sent one. */
class JwkKey(val kid: String?, val key: PublicKey)

/** Claims of a HOPE licenceToken: { iss, sub (uid), lic, dev (deviceId), plan, iat, exp }. */
class JwtClaims(private val values: Map<String, Any?>) {
    val issuer: String? get() = values["iss"] as? String
    val subject: String? get() = values["sub"] as? String
    val licenseId: String? get() = values["lic"] as? String
    val deviceId: String? get() = values["dev"] as? String
    val plan: String? get() = values["plan"] as? String
    val issuedAtSeconds: Long? get() = number("iat")
    val expiresAtSeconds: Long? get() = number("exp")

    private fun number(name: String): Long? = when (val v = values[name]) {
        is Long -> v
        is Double -> v.toLong()
        else -> null
    }
}

sealed class JwtResult {
    class Valid(val claims: JwtClaims) : JwtResult()
    class Invalid(val reason: String) : JwtResult()
}

/** Verifies ES256 (ECDSA P-256 + SHA-256) JWTs with plain java.security - no extra libraries. */
object Es256JwtVerifier {

    fun verify(token: String, keys: List<JwkKey>): JwtResult {
        val parts = token.trim().split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return JwtResult.Invalid("Token is not a JWT.")
        if (keys.isEmpty()) return JwtResult.Invalid("No public key is available.")

        val header = decodeObject(parts[0]) ?: return JwtResult.Invalid("Bad JWT header.")
        // Only ES256 is accepted: never "none" and never an HMAC algorithm.
        if (header["alg"] != "ES256") return JwtResult.Invalid("Unsupported JWT algorithm.")

        val rawSignature = decode(parts[2]) ?: return JwtResult.Invalid("Bad JWT signature encoding.")
        val der = joseToDer(rawSignature) ?: return JwtResult.Invalid("Bad JWT signature length.")
        val signedBytes = (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)

        val kid = header["kid"] as? String
        val ordered = keys.sortedBy { if (kid != null && it.kid == kid) 0 else 1 }
        val verified = ordered.any { candidate ->
            try {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(candidate.key)
                    update(signedBytes)
                    verify(der)
                }
            } catch (error: Exception) {
                false
            }
        }
        if (!verified) return JwtResult.Invalid("JWT signature does not match.")

        val payload = decodeObject(parts[1]) ?: return JwtResult.Invalid("Bad JWT payload.")
        return JwtResult.Valid(JwtClaims(payload))
    }

    /** Reads a JWK set (`{"keys":[...]}`), a single JWK, or a wrapper such as `{"ok":true,"keys":[...]}`. */
    fun parseJwks(json: String): List<JwkKey> {
        val root = MiniJson.parseObject(json) ?: return emptyList()
        val entries: List<Map<String, Any?>> = when {
            root["keys"] is List<*> -> (root["keys"] as List<*>).filterIsInstance<Map<*, *>>().map { entry ->
                entry.entries.associate { it.key.toString() to it.value }
            }
            root["kty"] != null -> listOf(root)
            root["key"] is Map<*, *> -> listOf((root["key"] as Map<*, *>).entries.associate { it.key.toString() to it.value })
            else -> emptyList()
        }
        return entries.mapNotNull { jwk ->
            if (jwk["kty"] != "EC" || jwk["crv"] != "P-256") return@mapNotNull null
            val x = jwk["x"] as? String ?: return@mapNotNull null
            val y = jwk["y"] as? String ?: return@mapNotNull null
            publicKeyFromJwk(x, y)?.let { JwkKey(jwk["kid"] as? String, it) }
        }
    }

    fun publicKeyFromJwk(x: String, y: String): PublicKey? {
        return try {
            val xBytes = decode(x) ?: return null
            val yBytes = decode(y) ?: return null
            if (xBytes.size > 32 || yBytes.size > 32) return null
            val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            val curve = parameters.getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, xBytes), BigInteger(1, yBytes))
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve))
        } catch (error: Exception) {
            null
        }
    }

    private fun decode(part: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(part)
    } catch (error: IllegalArgumentException) {
        null
    }

    private fun decodeObject(part: String): Map<String, Any?>? =
        decode(part)?.let { MiniJson.parseObject(String(it, Charsets.UTF_8)) }

    /** JWT signatures are raw R||S (64 bytes); java.security wants ASN.1 DER. */
    private fun joseToDer(signature: ByteArray): ByteArray? {
        if (signature.size != 64) return null
        fun integer(offset: Int): ByteArray {
            var start = offset
            while (start < offset + 31 && signature[start] == 0.toByte()) start++
            var bytes = signature.copyOfRange(start, offset + 32)
            if (bytes[0] < 0) bytes = byteArrayOf(0) + bytes // keep the INTEGER positive
            return bytes
        }
        val r = integer(0)
        val s = integer(32)
        val body = ByteArrayOutputStream().apply {
            write(0x02); write(r.size); write(r)
            write(0x02); write(s.size); write(s)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x30); write(body.size); write(body)
        }.toByteArray()
    }
}

/** What the app requires of a licenceToken before it lets HOPE run offline. */
object LicenseTokenPolicy {
    const val ISSUER = "hope-license"
    private const val CLOCK_ROLLBACK_TOLERANCE_SECONDS = 5 * 60L

    enum class Verdict { OK, BAD_ISSUER, WRONG_USER, WRONG_DEVICE, EXPIRED, CLOCK_TAMPERED }

    fun evaluate(
        claims: JwtClaims,
        firebaseUid: String,
        deviceId: String,
        nowSeconds: Long,
        latestSeenSeconds: Long
    ): Verdict {
        if (claims.issuer != ISSUER) return Verdict.BAD_ISSUER
        if (claims.subject != firebaseUid) return Verdict.WRONG_USER
        if (claims.deviceId != deviceId) return Verdict.WRONG_DEVICE
        // Setting the phone clock back must not revive an expired token.
        if (nowSeconds + CLOCK_ROLLBACK_TOLERANCE_SECONDS < latestSeenSeconds) return Verdict.CLOCK_TAMPERED
        val expires = claims.expiresAtSeconds ?: return Verdict.EXPIRED
        if (expires <= nowSeconds) return Verdict.EXPIRED
        return Verdict.OK
    }
}
