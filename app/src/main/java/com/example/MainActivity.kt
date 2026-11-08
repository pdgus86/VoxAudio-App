package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RecordingDetailScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.VoxFlowTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.util.configureAppCheck
import com.example.widget.VoxFlowWidgetProvider
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Step 1: Mandatory App Check configuration with intent
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        val shouldStartFromWidget = intent?.getBooleanExtra(VoxFlowWidgetProvider.EXTRA_START_RECORDING, false) ?: false

        setContent {
            VoxFlowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    AppNavigation(
                        shouldStartFromWidget = shouldStartFromWidget
                    )
                }
            }
        }
    }
}

@Composable
fun AppNavigation(
    shouldStartFromWidget: Boolean,
    modifier: Modifier = Modifier
) {
    val auth = Firebase.auth
    var currentUser by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { fAuth ->
            currentUser = fAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    if (currentUser == null) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = auth.currentUser
            },
            modifier = modifier
        )
    } else {
        val uid = currentUser!!.uid
        key(uid) {
            val viewModel: MainViewModel = viewModel(key = uid)

            // Permissions requester
            val permissionsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { /* handled */ }

            LaunchedEffect(Unit) {
                val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionsLauncher.launch(permissions.toTypedArray())

                if (shouldStartFromWidget) {
                    viewModel.startOneTouchRecording()
                }
            }

            val selectedDetail by viewModel.selectedRecordingDetail.collectAsState()

            if (selectedDetail != null) {
                RecordingDetailScreen(
                    recording = selectedDetail!!,
                    viewModel = viewModel,
                    onBack = { viewModel.clearSelectedRecordingDetail() },
                    modifier = modifier
                )
            } else {
                HomeScreen(
                    viewModel = viewModel,
                    modifier = modifier
                )
            }
        }
    }
}
