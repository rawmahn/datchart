package com.rawmahn.datchart.spike

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Server(
    val id: String,
    val name: String,
    val url: String,
    val defaultScope: String,
    val defaultTool: String,
    val defaultArgs: String,
)

val SERVERS = listOf(
    Server(
        id = "posthog",
        name = "PostHog",
        url = "https://mcp.posthog.com/mcp",
        defaultScope = "openid profile email introspection user:read organization:read project:read query:read insight:read dashboard:read",
        // PostHog's MCP exposes one `exec` tool that takes CLI-style commands.
        defaultTool = "exec",
        defaultArgs = """{"command": "call --json execute-sql {\"query\": \"SELECT count() AS events_24h FROM events WHERE timestamp > now() - INTERVAL 1 DAY\"}", "context": "Datchart OAuth spike: one read-only query"}""",
    ),
    Server(
        id = "tally",
        name = "Tally",
        url = "https://api.tally.so/mcp",
        defaultScope = "mcp",
        defaultTool = "list_forms",
        defaultArgs = """{"limit": 5}""",
    ),
)

enum class Redirect(val label: String, val uri: String) {
    // RFC 8252 loopback. Tally accepts only https, http://localhost/127.0.0.1 or oob (checked 2026-11-20).
    LOOPBACK("Loopback 127.0.0.1", "http://127.0.0.1:47823/callback"),
    // Private-use scheme. PostHog accepts it, Tally's DCR rejects it.
    SCHEME("Custom scheme", "com.rawmahn.datchart.spike:/oauth"),
}

/** Everything the spike does, with a log Roman can copy into the issue. */
object Spike {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val log = mutableStateListOf<String>()
    val version = mutableIntStateOf(0) // bump to recompose status lines

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("spike", Context.MODE_PRIVATE) }
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var loopback: ServerSocket? = null
    private var finishing = false
    private val logFile by lazy { File(app.filesDir, "log.txt") }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // The log survives the process being killed behind the Custom Tab, so "Copy log" has everything.
        runCatching { if (logFile.exists()) log.addAll(logFile.readLines().takeLast(1000)) }
        say("app started (build ${appVersion()}, Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}); ${backgroundLimits()}")
    }

    fun say(line: String) {
        val stamped = "${time.format(Date())} $line"
        scope.launch {
            log.add(stamped)
            runCatching { logFile.appendText(stamped.replace("\n", "\n    ") + "\n") }
        }
    }

    fun clearLog() {
        log.clear()
        runCatching { logFile.delete() }
    }

    private fun appVersion(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Data Saver and the battery "restricted" setting both cut an app's network while it is in the background. */
    private fun backgroundLimits(): String {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val dataSaver = when (cm?.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on for this app"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on, app exempt"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "off"
            else -> "?"
        }
        val restricted = if (Build.VERSION.SDK_INT >= 28) app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted.toString() else "n/a"
        return "Data Saver: $dataSaver, background restricted: $restricted"
    }

    fun run(label: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                say("✗ $label: ${e.javaClass.simpleName}: ${e.message}")
            }
            version.intValue++
        }
    }

    // --- Stored state -------------------------------------------------------------------------

    fun tokens(server: Server): JSONObject? = prefs.getString("tokens:${server.id}", null)?.let(::JSONObject)

    fun status(server: Server): String {
        val t = tokens(server) ?: return "signed out"
        val left = (t.optLong("expires_at") - System.currentTimeMillis()) / 1000
        val exp = if (t.has("expires_at")) (if (left > 0) "expires in ${left / 60} min" else "EXPIRED ${-left / 60} min ago") else "no expiry given"
        val refresh = if (t.optString("refresh_token").isNotEmpty()) "refresh token ✓" else "no refresh token"
        return "signed in via ${t.optString("redirect_mode")} · $exp · $refresh"
    }

    fun forget(server: Server) {
        prefs.edit().remove("tokens:${server.id}").apply()
        Redirect.entries.forEach { prefs.edit().remove("client:${server.id}:${it.name}").apply() }
        say("${server.name}: forgot tokens and client registrations")
        version.intValue++
    }

    // --- Discovery and registration -----------------------------------------------------------

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("GET $url -> ${it.code}: ${body.take(200)}")
            JSONObject(body)
        }
    }

    private suspend fun discover(server: Server): JSONObject {
        val probe = withContext(Dispatchers.IO) {
            val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"datchart-spike","version":"0"}}}"""
            http.newCall(
                Request.Builder().url(server.url).post(init.toRequestBody(json))
                    .header("Accept", "application/json, text/event-stream").build(),
            ).execute().use { it.code to it.header("WWW-Authenticate") }
        }
        say("${server.name}: unauthenticated POST -> ${probe.first}, WWW-Authenticate: ${probe.second}")
        val prmUrl = OAuth.resourceMetadataUrl(probe.second) ?: OAuth.defaultResourceMetadataUrl(server.url)
        val prm = getJson(prmUrl)
        val issuer = prm.getJSONArray("authorization_servers").getString(0)
        val resource = prm.optString("resource", server.url)
        say("${server.name}: resource=$resource, authorization server=$issuer")
        var asm: JSONObject? = null
        for (u in OAuth.authServerMetadataUrls(issuer)) {
            asm = runCatching { getJson(u) }.getOrNull()
            if (asm != null) {
                say("${server.name}: AS metadata at $u")
                break
            }
        }
        asm ?: error("no authorization server metadata for $issuer")
        val methods = asm.optJSONArray("code_challenge_methods_supported")?.toString()
        say("${server.name}: authorize=${asm.optString("authorization_endpoint")} token=${asm.optString("token_endpoint")} register=${asm.optString("registration_endpoint")} pkce=$methods")
        return asm.put("x_resource", resource)
    }

    private suspend fun register(server: Server, asm: JSONObject, redirect: Redirect): String {
        val key = "client:${server.id}:${redirect.name}"
        prefs.getString(key, null)?.let {
            say("${server.name}: reusing client_id $it")
            return it
        }
        val endpoint = asm.optString("registration_endpoint").ifEmpty { error("server has no registration_endpoint (DCR)") }
        val body = JSONObject()
            .put("client_name", "Datchart OAuth spike")
            .put("redirect_uris", JSONArray().put(redirect.uri))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("application_type", "native")
        val res = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(endpoint).post(body.toString().toRequestBody(json)).build()).execute().use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) error("DCR with ${redirect.uri} -> ${it.code}: ${text.take(300)}")
                JSONObject(text)
            }
        }
        val id = res.getString("client_id")
        say("${server.name}: registered client_id $id for ${redirect.uri} (redirect_uris back: ${res.optJSONArray("redirect_uris")})")
        prefs.edit().putString(key, id).apply()
        return id
    }

    // --- Sign-in ------------------------------------------------------------------------------

    fun signIn(activity: Activity, server: Server, redirect: Redirect, scopeText: String) = run("${server.name} sign-in") {
        val asm = discover(server)
        val clientId = register(server, asm, redirect)
        val verifier = OAuth.randomToken()
        val state = OAuth.randomToken(16)
        val pending = JSONObject()
            .put("server", server.id).put("state", state).put("verifier", verifier)
            .put("client_id", clientId).put("redirect", redirect.name)
            .put("token_endpoint", asm.getString("token_endpoint")).put("resource", asm.getString("x_resource"))
        prefs.edit().putString("pending", pending.toString()).apply()
        val url = OAuth.authorizeUrl(
            asm.getString("authorization_endpoint"), clientId, redirect.uri,
            OAuth.challenge(verifier), state, scopeText, asm.getString("x_resource"),
        )
        if (redirect == Redirect.LOOPBACK) startLoopback()
        say("${server.name}: opening Custom Tab -> ${url.take(160)}…")
        CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
    }

    /** One-shot HTTP listener on 127.0.0.1:47823 for the loopback redirect. */
    private fun startLoopback() {
        loopback?.runCatching { close() }
        val socket = ServerSocket(47823, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10 * 60 * 1000 }
        loopback = socket
        say("loopback: listening on 127.0.0.1:47823")
        Thread {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { conn ->
                        val line = BufferedReader(InputStreamReader(conn.getInputStream())).readLine().orEmpty()
                        val (path, query) = OAuth.requestTarget(line) ?: ("" to null)
                        val hit = path == "/callback"
                        val html = if (hit) {
                            "<html><body style='font:20px sans-serif;padding:24px'><p>Signed in. Datchart should come back by itself.</p>" +
                                "<p><a href='com.rawmahn.datchart.spike:/back'>Back to Datchart</a></p></body></html>"
                        } else ""
                        val status = if (hit) "200 OK" else "404 Not Found"
                        conn.getOutputStream().write(
                            "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html".toByteArray(),
                        )
                        if (hit) {
                            socket.close()
                            // Don't exchange the code here: the app is behind the Custom Tab, and Android cuts a
                            // background app's network (first phone run: "Unable to resolve host oauth.posthog.com").
                            // Keep it and finish in onResume, once the app is in front again.
                            prefs.edit().putString("callback", JSONObject().put("query", query.orEmpty()).put("via", "loopback").toString()).commit()
                            say("loopback: got $path, finishing once the app is back in front")
                            scope.launch {
                                // Try to come back on top of the Custom Tab by ourselves; Android may block this
                                // background start, in which case the link on the page does it.
                                runCatching {
                                    app.startActivity(
                                        Intent(app, MainActivity::class.java).addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                        ),
                                    )
                                }.onFailure { say("loopback: could not bring the app back: ${it.message}") }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!socket.isClosed) say("loopback: stopped: ${e.message}")
            }
        }.start()
    }

    /** Called from MainActivity for com.rawmahn.datchart.spike:/… intents. */
    fun onIntent(uri: Uri) {
        when (uri.path) {
            "/oauth" -> prefs.edit().putString("callback", JSONObject().put("query", uri.encodedQuery.orEmpty()).put("via", "custom scheme").toString()).commit()
            "/back" -> say("back link tapped (the app did not come back by itself)")
            else -> say("unknown intent $uri")
        }
    }

    /** From MainActivity.onResume: finish a sign-in whose redirect arrived, now that the app is in front. */
    fun resume() {
        if (finishing) return
        val cb = prefs.getString("callback", null)?.let(::JSONObject) ?: return
        finishing = true
        scope.launch {
            try {
                finish(OAuth.parseQuery(cb.optString("query")), cb.optString("via"))
            } finally {
                finishing = false
            }
        }
    }

    private suspend fun finish(params: Map<String, String>, via: String) {
        try {
            val pending = prefs.getString("pending", null)?.let(::JSONObject) ?: error("no sign-in in progress")
            params["error"]?.let { error("authorization error: $it ${params["error_description"].orEmpty()}") }
            if (params["state"] != pending.getString("state")) error("state mismatch")
            val code = params["code"] ?: error("no code in redirect")
            val server = SERVERS.first { it.id == pending.getString("server") }
            val redirect = Redirect.valueOf(pending.getString("redirect"))
            say("${server.name}: code received via $via (iss=${params["iss"]})")
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", redirect.uri).add("client_id", pending.getString("client_id"))
                .add("code_verifier", pending.getString("verifier")).add("resource", pending.getString("resource"))
                .build()
            val t = tokenRequest(pending.getString("token_endpoint"), form)
            save(server, t, pending, redirect.label)
            prefs.edit().remove("pending").remove("callback").apply()
            say("${server.name}: ✓ signed in. ${describe(t)}")
        } catch (e: IOException) {
            // The request never got an answer, so the code is unused: keep it and try again on the next resume.
            say("✗ finishing sign-in: ${e.javaClass.simpleName}: ${e.message} (${backgroundLimits()}); will retry when the app comes to the front again")
        } catch (e: Throwable) {
            prefs.edit().remove("callback").apply()
            say("✗ finishing sign-in: ${e.javaClass.simpleName}: ${e.message}")
        }
        version.intValue++
    }

    private suspend fun tokenRequest(endpoint: String, form: FormBody): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(endpoint).post(form).header("Accept", "application/json").build()).execute().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("token endpoint -> ${it.code}: ${text.take(300)}")
            JSONObject(text)
        }
    }

    private fun describe(t: JSONObject) =
        "token_type=${t.optString("token_type")}, expires_in=${t.opt("expires_in")}, scope=${t.optString("scope").take(200)}, " +
            "refresh_token=${if (t.optString("refresh_token").isNotEmpty()) "yes" else "no"}, access_token length=${t.optString("access_token").length}"

    private fun save(server: Server, t: JSONObject, meta: JSONObject, mode: String) {
        val stored = JSONObject()
            .put("access_token", t.getString("access_token"))
            .put("refresh_token", t.optString("refresh_token").ifEmpty { tokens(server)?.optString("refresh_token").orEmpty() })
            .put("scope", t.optString("scope"))
            .put("client_id", meta.getString("client_id"))
            .put("token_endpoint", meta.getString("token_endpoint"))
            .put("resource", meta.getString("resource"))
            .put("redirect_mode", mode)
            .put("obtained_at", System.currentTimeMillis())
        if (t.has("expires_in")) stored.put("expires_at", System.currentTimeMillis() + t.getLong("expires_in") * 1000)
        prefs.edit().putString("tokens:${server.id}", stored.toString()).apply()
    }

    suspend fun refresh(server: Server) {
        val t = tokens(server) ?: error("not signed in")
        val rt = t.optString("refresh_token").ifEmpty { error("no refresh token") }
        val old = t.getString("access_token")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token").add("refresh_token", rt)
            .add("client_id", t.getString("client_id")).add("resource", t.getString("resource"))
            .build()
        val n = tokenRequest(t.getString("token_endpoint"), form)
        save(server, n, t, t.optString("redirect_mode"))
        val rotated = n.optString("refresh_token").let { it.isNotEmpty() && it != rt }
        say("${server.name}: ✓ refreshed (new access token: ${n.getString("access_token") != old}, refresh token rotated: $rotated). ${describe(n)}")
    }

    /** Bearer token, refreshed first when it has expired (or is within a minute of it). */
    private suspend fun accessToken(server: Server): String {
        val t = tokens(server) ?: error("not signed in")
        if (t.has("expires_at") && t.getLong("expires_at") < System.currentTimeMillis() + 60_000) {
            say("${server.name}: access token expired, refreshing")
            refresh(server)
        }
        return tokens(server)!!.getString("access_token")
    }

    // --- MCP (official Kotlin SDK, Streamable HTTP) -------------------------------------------

    private suspend fun <T> mcp(server: Server, block: suspend (io.modelcontextprotocol.kotlin.sdk.client.Client) -> T): T {
        val token = accessToken(server)
        val client = HttpClient(OkHttp) { install(SSE) }
        try {
            val mcp = client.mcpStreamableHttp(server.url, requestBuilder = { headers.append(HttpHeaders.Authorization, "Bearer $token") })
            say("${server.name}: MCP connected to ${mcp.serverVersion?.name} ${mcp.serverVersion?.version}")
            try {
                return block(mcp)
            } finally {
                runCatching { mcp.close() }
            }
        } finally {
            client.close()
        }
    }

    fun listTools(server: Server) = run("${server.name} tools/list") {
        mcp(server) { c ->
            val tools = c.listTools().tools
            val ro = tools.count { it.annotations?.readOnlyHint == true }
            say("${server.name}: ${tools.size} tools ($ro with readOnlyHint): " + tools.joinToString(", ") { t ->
                t.name + if (t.annotations?.readOnlyHint == true) "[ro]" else ""
            }.take(1500))
        }
    }

    fun callTool(server: Server, tool: String, argsText: String) = run("${server.name} tools/call") {
        val args = toMap(JSONObject(argsText.ifBlank { "{}" }))
        mcp(server) { c ->
            val started = System.currentTimeMillis()
            val r = c.callTool(tool, args)
            val text = r.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            say("${server.name}: $tool -> isError=${r.isError ?: false} in ${System.currentTimeMillis() - started} ms, ${text.length} chars:\n${text.take(3000)}")
        }
    }

    fun refreshNow(server: Server) = run("${server.name} refresh") { refresh(server) }

    private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> unwrap(o.get(k)) }

    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }
}
