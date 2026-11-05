package com.jarvis.assistant.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class LicenseCoreTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private fun enc(text: String) = b64.encodeToString(text.toByteArray())

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun fixed32(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    private fun derToJose(der: ByteArray): ByteArray {
        var i = 2
        val rl = der[i + 1].toInt(); val r = der.copyOfRange(i + 2, i + 2 + rl); i += 2 + rl
        val sl = der[i + 1].toInt(); val s = der.copyOfRange(i + 2, i + 2 + sl)
        fun fix(x: ByteArray): ByteArray { val t = x.dropWhile { it == 0.toByte() }.toByteArray(); return ByteArray(32 - t.size) + t }
        return fix(r) + fix(s)
    }

    private fun jwt(key: PrivateKey, header: String, payload: String): String {
        val input = enc(header) + "." + enc(payload)
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(input.toByteArray()); sign() }
        return input + "." + b64.encodeToString(derToJose(der))
    }

    private fun jwks(pub: ECPublicKey, kid: String = "k1") =
        """{"keys":[{"kty":"EC","crv":"P-256","kid":"$kid","x":"${b64.encodeToString(fixed32(pub.w.affineX))}","y":"${b64.encodeToString(fixed32(pub.w.affineY))}"}]}"""

    private val payload =
        """{"iss":"hope-license","sub":"uid123","lic":"lic_abc","dev":"dev-1","plan":"business","iat":1793449852,"exp":1793709052}"""

    @Test
    fun validTokenVerifiesAndExposesClaims() {
        val pair = newKeyPair()
        val keys = Es256JwtVerifier.parseJwks(jwks(pair.public as ECPublicKey))
        val result = Es256JwtVerifier.verify(jwt(pair.private, """{"alg":"ES256","typ":"JWT"}""", payload), keys)
        assertTrue(result is JwtResult.Valid)
        val claims = (result as JwtResult.Valid).claims
        assertEquals("uid123", claims.subject)
        assertEquals("dev-1", claims.deviceId)
        assertEquals("lic_abc", claims.licenseId)
        assertEquals(1793709052L, claims.expiresAtSeconds)
    }

    @Test
    fun manySignaturesVerify() {
        val pair = newKeyPair()
        val keys = Es256JwtVerifier.parseJwks(jwks(pair.public as ECPublicKey))
        repeat(200) {
            val token = jwt(pair.private, """{"alg":"ES256"}""", """{"sub":"u$it"}""")
            assertTrue(Es256JwtVerifier.verify(token, keys) is JwtResult.Valid)
        }
    }

    @Test
    fun forgedOrTamperedTokensAreRejected() {
        val pair = newKeyPair()
        val other = newKeyPair()
        val keys = Es256JwtVerifier.parseJwks(jwks(pair.public as ECPublicKey))
        val token = jwt(pair.private, """{"alg":"ES256"}""", payload)
        val parts = token.split('.')
        assertTrue(Es256JwtVerifier.verify(parts[0] + "." + enc(payload.replace("business", "x")) + "." + parts[2], keys) is JwtResult.Invalid)
        assertTrue(Es256JwtVerifier.verify(token, Es256JwtVerifier.parseJwks(jwks(other.public as ECPublicKey))) is JwtResult.Invalid)
        assertTrue(Es256JwtVerifier.verify(enc("""{"alg":"none"}""") + "." + parts[1] + "." + parts[2], keys) is JwtResult.Invalid)
        assertTrue(Es256JwtVerifier.verify(jwt(pair.private, """{"alg":"HS256"}""", payload), keys) is JwtResult.Invalid)
        assertTrue(Es256JwtVerifier.verify(parts[0] + "." + parts[1] + ".", keys) is JwtResult.Invalid)
        assertTrue(Es256JwtVerifier.verify(token, emptyList()) is JwtResult.Invalid)
    }

    @Test
    fun offlinePolicyChecksUserDeviceExpiryAndClock() {
        val pair = newKeyPair()
        val keys = Es256JwtVerifier.parseJwks(jwks(pair.public as ECPublicKey))
        val claims = (Es256JwtVerifier.verify(jwt(pair.private, """{"alg":"ES256"}""", payload), keys) as JwtResult.Valid).claims
        val now = 1793500000L
        fun verdict(uid: String = "uid123", dev: String = "dev-1", at: Long = now, seen: Long = now) =
            LicenseTokenPolicy.evaluate(claims, uid, dev, at, seen)
        assertEquals(LicenseTokenPolicy.Verdict.OK, verdict())
        assertEquals(LicenseTokenPolicy.Verdict.WRONG_USER, verdict(uid = "other"))
        assertEquals(LicenseTokenPolicy.Verdict.WRONG_DEVICE, verdict(dev = "dev-2"))
        assertEquals(LicenseTokenPolicy.Verdict.EXPIRED, verdict(at = 1793709052L))
        assertEquals(LicenseTokenPolicy.Verdict.CLOCK_TAMPERED, verdict(at = now - 3600, seen = now))
        assertEquals(LicenseTokenPolicy.Verdict.OK, verdict(at = now - 120, seen = now))
    }

    @Test
    fun jwksFormatsAreAccepted() {
        val pub = newKeyPair().public as ECPublicKey
        val set = jwks(pub)
        assertEquals(1, Es256JwtVerifier.parseJwks(set).size)
        assertEquals(1, Es256JwtVerifier.parseJwks("""{"ok":true,${set.removePrefix("{")}""").size)
        assertTrue(Es256JwtVerifier.parseJwks("""{"keys":[{"kty":"RSA","n":"x","e":"AQAB"}]}""").isEmpty())
        assertTrue(Es256JwtVerifier.parseJwks("not json").isEmpty())
    }

    @Test
    fun licenceKeysAreFormattedAndValidated() {
        val key = "HOPE-D0J94-40PVM-CBFN1-34RHH"
        assertEquals(key, LicenseKeyFormat.format("hope-d0j94-40pvm-cbfn1-34rhh"))
        assertEquals(key, LicenseKeyFormat.format("HOPE D0J94 40PVM CBFN1 34RHH"))
        assertEquals(key, LicenseKeyFormat.format("d0j9440pvmcbfn134rhh"))
        assertEquals("HOPE-D0J94-40PVM-CBFN1-34RH1", LicenseKeyFormat.format("HOPE-DOJ94-4OPVM-CBFNI-34RHL"))
        assertEquals("HOPE-D0J94-4", LicenseKeyFormat.format("HOPE-D0J94-4"))
        assertEquals("HOP", LicenseKeyFormat.format("HOP"))
        assertEquals(key, LicenseKeyFormat.format(key + "ZZZZ"))
        assertEquals("", LicenseKeyFormat.format(""))
        assertTrue(LicenseKeyFormat.isComplete(key))
        assertFalse(LicenseKeyFormat.isComplete("HOPE-D0J94-40PVM"))
        assertFalse(LicenseKeyFormat.isValid("HOPE-D0J94-40PVM-CBFN1-34RHU"))
    }

    @Test
    fun miniJsonParsesServerResponsesAndRejectsGarbage() {
        val parsed = MiniJson.parseObject(
            """{"ok":true,"licensed":true,"license":{"id":"lic_1","maxDevices":1,"validUntil":null},"devices":[{"name":"Pixel 7"}]}"""
        )
        assertNotNull(parsed)
        assertEquals(true, parsed!!["licensed"])
        assertEquals(1L, (parsed["license"] as Map<*, *>)["maxDevices"])
        assertEquals("Pixel 7", ((parsed["devices"] as List<*>)[0] as Map<*, *>)["name"])
        assertEquals("a\"b\n\u0939", MiniJson.parseObject("""{"m":"a\"b\n\u0939"}""")!!["m"])
        assertNull(MiniJson.parseObject("<html>502 Bad Gateway</html>"))
        assertNull(MiniJson.parseObject("{"))
        assertNull(MiniJson.parseObject("""{"a":1} x"""))
        assertNull(MiniJson.parseObject("[".repeat(200) + "]".repeat(200)))
    }
}
