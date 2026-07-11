package com.novaos.novakeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.novaos.novakeyboard.ui.theme.NovaKeyboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NovaKeyboardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetupScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun SetupScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testText by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val currentVersion = "2.0.3"
    
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Update Check") },
            text = { Text(updateStatus ?: "Checking for updates...") },
            confirmButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Nova Keyboard Setup",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                context.startActivity(intent)
            },
            shape = RectangleShape,

            modifier = Modifier.fillMaxWidth(0.8f).padding(4.dp)
        ) {
            Text("1. Enable Nova Keyboard")
        }

        Button(
            onClick = {
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm?.showInputMethodPicker()
            },
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(0.8f).padding(4.dp)
        ) {
            Text("2. Select Nova Keyboard")
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        Button(
            onClick = {
                if (!hasMicPermission) {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(0.8f).padding(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasMicPermission) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (hasMicPermission) "Microphone Permission Granted" else "3. Grant Mic Permission (Voice)")
        }
        
        Button(
            onClick = {
                isCheckingUpdates = true
                showUpdateDialog = true
                updateStatus = "Connecting to GitHub..."
                scope.launch {
                    val result = checkGitHubForUpdates()
                    updateStatus = if (result == null) {
                        "Failed to check for updates. Please try again later."
                    } else if (result == currentVersion) {
                        "Nova Keyboard is up to date! (v$result)"
                    } else {
                        "A new version is available: v$result\nVisit GitHub to download."
                    }
                    isCheckingUpdates = false
                }
            },
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(0.8f).padding(4.dp),
            enabled = !isCheckingUpdates,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text(if (isCheckingUpdates) "Checking..." else "Check for Updates")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text("Test your keyboard here") },
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Version: 2.0.3",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Build: 203.260710.2303",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SetupScreenPreview() {
    NovaKeyboardTheme {
        SetupScreen()
    }
}

private suspend fun checkGitHubForUpdates(): String? = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://api.github.com/repos/NovaApplications/Nova-Keyboard/releases/latest")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val tagName = json.getString("tag_name")
            // Strip 'v' if present (e.g. v2.0.3 -> 2.0.3)
            tagName.replace("v", "")
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e("NovaKeyboard", "Update check failed", e)
        null
    }
}
