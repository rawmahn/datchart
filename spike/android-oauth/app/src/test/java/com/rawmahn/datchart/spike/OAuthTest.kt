package com.rawmahn.datchart.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthTest {
    @Test fun pkceMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun randomTokenIsUrlSafe() {
        val t = OAuth.randomToken()
        assertEquals(43, t.length)
        assertTrue(t.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test fun resourceMetadataFromRealHeaders() {
        // As sent by the two servers on 2026-11-20.
        assertEquals(
            "https://mcp.posthog.com/.well-known/oauth-protected-resource/mcp",
            OAuth.resourceMetadataUrl("""Bearer resource_metadata="https://mcp.posthog.com/.well-known/oauth-protected-resource/mcp""""),
        )
        assertEquals(
            "https://api.tally.so/.well-known/oauth-protected-resource/mcp",
            OAuth.resourceMetadataUrl("""Bearer resource_metadata="https://api.tally.so/.well-known/oauth-protected-resource/mcp", scope="mcp""""),
        )
        assertNull(OAuth.resourceMetadataUrl("Bearer"))
        assertNull(OAuth.resourceMetadataUrl(null))
    }

    @Test fun defaultResourceMetadata() {
        assertEquals(
            "https://api.tally.so/.well-known/oauth-protected-resource/mcp",
            OAuth.defaultResourceMetadataUrl("https://api.tally.so/mcp"),
        )
    }

    @Test fun authServerMetadataInsertsIssuerPath() {
        // Tally's issuer has a path; its metadata lives at the RFC 8414 inserted location.
        assertEquals(
            "https://api.tally.so/.well-known/oauth-authorization-server/mcp",
            OAuth.authServerMetadataUrls("https://api.tally.so/mcp").first(),
        )
        assertEquals(
            listOf(
                "https://oauth.posthog.com/.well-known/oauth-authorization-server",
                "https://oauth.posthog.com/.well-known/openid-configuration",
            ),
            OAuth.authServerMetadataUrls("https://oauth.posthog.com"),
        )
    }

    @Test fun authorizeUrlEncodesEverything() {
        val url = OAuth.authorizeUrl(
            "https://api.tally.so/oauth/authorize", "cid", "http://127.0.0.1:47823/callback",
            "chal", "st", "mcp", "https://api.tally.so/mcp",
        )
        assertTrue(url.startsWith("https://api.tally.so/oauth/authorize?response_type=code&client_id=cid&"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A47823%2Fcallback"))
        assertTrue(url.contains("scope=mcp"))
        assertTrue(url.contains("resource=https%3A%2F%2Fapi.tally.so%2Fmcp"))
        val spaced = OAuth.authorizeUrl("https://x/a?p=1", "c", "r", "h", "s", "openid query:read", "res")
        assertTrue(spaced.contains("?p=1&response_type"))
        assertTrue(spaced.contains("scope=openid%20query%3Aread"))
    }

    @Test fun parsesLoopbackRequest() {
        val (path, query) = OAuth.requestTarget("GET /callback?code=a%2Fb&state=xyz HTTP/1.1")!!
        assertEquals("/callback", path)
        assertEquals(mapOf("code" to "a/b", "state" to "xyz"), OAuth.parseQuery(query))
        assertEquals("/favicon.ico" to null, OAuth.requestTarget("GET /favicon.ico HTTP/1.1"))
        assertEquals(mapOf("error" to "access_denied", "x" to ""), OAuth.parseQuery("?error=access_denied&x"))
    }
}
