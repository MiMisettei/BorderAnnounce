package com.mystudio.borderannounce

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.mystudio.borderannounce.circuit.HomeScreen
import com.mystudio.borderannounce.di.ActivityKey
import com.mystudio.borderannounce.ui.theme.BorderAnnounceAppTheme
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.navstack.rememberSaveableNavStack
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.overlay.ContentWithOverlays
import com.slack.circuit.retained.CircuitRetainedSettings
import com.slack.circuit.retained.ExperimentalCircuitRetainedApi
import com.slack.circuit.sharedelements.SharedElementTransitionLayout
import com.slack.circuitx.gesturenavigation.GestureNavigationDecorationFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import java.util.Locale

@ActivityKey(MainActivity::class)
@ContributesIntoMap(AppScope::class, binding = binding<Activity>())
class MainActivity
    constructor(
        private val circuit: Circuit,
    ) : ComponentActivity(), TextToSpeech.OnInitListener {

        private var tts: TextToSpeech? = null

        private val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            
            if (fineLocationGranted || coarseLocationGranted) {
                startLocationService()
            } else {
                speak("位置情報の利用が拒否されました。設定から許可してください。")
            }
        }

        @OptIn(ExperimentalSharedTransitionApi::class, ExperimentalCircuitRetainedApi::class)
        override fun onCreate(savedInstanceState: Bundle?) {
            CircuitRetainedSettings.useFirstParty = true
            enableEdgeToEdge()
            super.onCreate(savedInstanceState)

            tts = TextToSpeech(this, this)

            setContent {
                BorderAnnounceAppTheme {
                    val navStack = rememberSaveableNavStack(root = HomeScreen)
                    val navigator = rememberCircuitNavigator(navStack)

                    CircuitCompositionLocals(circuit) {
                        SharedElementTransitionLayout {
                            ContentWithOverlays {
                                NavigableCircuitContent(
                                    navigator = navigator,
                                    navStack = navStack,
                                    decoratorFactory =
                                        remember {
                                            GestureNavigationDecorationFactory()
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }

        override fun onInit(status: Int) {
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.JAPANESE)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    checkAndRequestPermissions()
                }
            }
        }

        private fun checkAndRequestPermissions() {
            val hasFineLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasFineLocation) {
                startLocationService()
            } else {
                // 💡 初回起動時のシステム権限リクエストダイアログ表示前のアナウンス音声を完全に削除（サイレント化）
                val permissionsToRequest = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }

                requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }

        private fun startLocationService() {
            val serviceIntent = Intent(this, LocationService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        }

        private fun speak(text: String) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "app_speech")
        }

        override fun onDestroy() {
            tts?.stop()
            tts?.shutdown()
            super.onDestroy()
        }
    }