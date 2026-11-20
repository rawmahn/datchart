package com.rawmahn.datchart.spike

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Spike.init(this)
        handle(intent)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "com.rawmahn.datchart.spike") Spike.onIntent(uri)
        intent.data = null
    }

    @Composable
    private fun Screen() {
        var redirect by rememberSaveable { mutableStateOf(Redirect.LOOPBACK) }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Datchart OAuth spike", style = MaterialTheme.typography.headlineSmall)
            Text("Redirect for the next sign-in:")
            Redirect.entries.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = redirect == r, onClick = { redirect = r })
                    Text("${r.label}  (${r.uri})", fontSize = 13.sp)
                }
            }
            SERVERS.forEach { ServerCard(it, redirect) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { copyLog() }) { Text("Copy log") }
                OutlinedButton(onClick = { Spike.log.clear() }) { Text("Clear") }
            }
            SelectionContainer {
                Text(Spike.log.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ServerCard(server: Server, redirect: Redirect) {
        var scope by rememberSaveable(server.id) { mutableStateOf(server.defaultScope) }
        var tool by rememberSaveable(server.id + "t") { mutableStateOf(server.defaultTool) }
        var args by rememberSaveable(server.id + "a") { mutableStateOf(server.defaultArgs) }
        val v = Spike.version.intValue
        val status = remember(v) { Spike.status(server) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Text("${server.url}\n$status", fontSize = 12.sp)
                OutlinedTextField(scope, { scope = it }, label = { Text("scope") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { Spike.signIn(this@MainActivity, server, redirect, scope) }) { Text("Sign in") }
                    OutlinedButton(onClick = { Spike.listTools(server) }) { Text("List tools") }
                    OutlinedButton(onClick = { Spike.refreshNow(server) }) { Text("Refresh token") }
                    OutlinedButton(onClick = { Spike.forget(server) }) { Text("Forget") }
                }
                OutlinedTextField(tool, { tool = it }, label = { Text("tool") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(args, { args = it }, label = { Text("arguments (JSON)") }, modifier = Modifier.fillMaxWidth())
                FilledTonalButton(onClick = { Spike.callTool(server, tool, args) }) { Text("Call tool") }
            }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Datchart spike log", Spike.log.joinToString("\n")))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }
}
