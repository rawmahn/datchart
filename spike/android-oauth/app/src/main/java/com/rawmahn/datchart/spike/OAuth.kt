package com.rawmahn.datchart.spike

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pure OAuth helpers (no Android), so unit tests run on the JVM. */
object OAuth {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun randomToken(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(b64::encodeToString)

    /** RFC 7636 S256: BASE64URL(SHA256(ascii(verifier))). */
    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** `Bearer resource_metadata="…", scope="…"` -> the resource_metadata URL. */
    fun resourceMetadataUrl(wwwAuthenticate: String?): String? =
        wwwAuthenticate?.let { Regex("""resource_metadata="([^"]+)"""").find(it)?.groupValues?.get(1) }

    /** RFC 9728 well-known location, used when the 401 carries no resource_metadata. */
    fun defaultResourceMetadataUrl(resource: String): String {
        val u = URI(resource)
        return "${u.scheme}://${u.rawAuthority}/.well-known/oauth-protected-resource${u.rawPath.trimEnd('/')}"
    }

    /**
     * Where to look for the authorization server's metadata, in order (MCP spec 2025-06-18):
     * RFC 8414 with the issuer path inserted, then OpenID Connect in both forms.
     */
    fun authServerMetadataUrls(issuer: String): List<String> {
        val u = URI(issuer)
        val origin = "${u.scheme}://${u.rawAuthority}"
        val path = u.rawPath.trimEnd('/')
        return listOf(
            "$origin/.well-known/oauth-authorization-server$path",
            "$origin/.well-known/openid-configuration$path",
            "${issuer.trimEnd('/')}/.well-known/openid-configuration",
        ).distinct()
    }

    fun authorizeUrl(
        endpoint: String,
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
        scope: String,
        resource: String,
    ): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "resource" to resource,
        )
        if (scope.isNotBlank()) params["scope"] = scope.trim()
        val sep = if (endpoint.contains('?')) "&" else "?"
        return endpoint + sep + form(params)
    }

    fun form(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    /** Query string (`a=1&b=2`) -> map; tolerates a leading `?` and an empty value. */
    fun parseQuery(query: String?): Map<String, String> =
        query.orEmpty().removePrefix("?").split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            if (i < 0) dec(it) to "" else dec(it.substring(0, i)) to dec(it.substring(i + 1))
        }

    /** First line of an HTTP request (`GET /callback?code=… HTTP/1.1`) -> path and query. */
    fun requestTarget(requestLine: String): Pair<String, String?>? {
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2) return null
        val target = parts[1]
        val q = target.indexOf('?')
        return if (q < 0) target to null else target.substring(0, q) to target.substring(q + 1)
    }

    /** Parameter names of a redirect query, for the log: values left out, except error and error_description. */
    fun describeParams(query: String?): String {
        val params = parseQuery(query)
        if (params.isEmpty()) return "(no parameters)"
        return "with " + params.entries.joinToString(", ") { (k, v) ->
            if (k == "error" || k == "error_description") "$k=$v" else k
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}
