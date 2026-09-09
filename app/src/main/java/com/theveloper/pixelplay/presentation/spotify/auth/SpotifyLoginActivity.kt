package com.theveloper.pixelplay.presentation.spotify.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import com.theveloper.pixelplay.data.worker.SpotifySyncWorker
import com.theveloper.pixelplay.ui.theme.PixelPlayTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Pantalla puente del login de Spotify.
 *
 * Abre el navegador con la página de autorización y vuelve a recibir el control por el
 * intent-filter de `pixelplay://spotify-callback`. `launchMode="singleTask"` es lo que
 * hace que la vuelta llegue a *esta* instancia (por `onNewIntent`) en vez de apilar otra.
 */
@AndroidEntryPoint
class SpotifyLoginActivity : ComponentActivity() {

    @Inject
    lateinit var authManager: SpotifyAuthManager

    private var awaitingBrowser = false
    private var firstResumeConsumed = false
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PixelPlayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
                        Text(
                            text = "Connecting to Spotify…",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (consumeCallback(intent)) return

        if (!authManager.hasClientId()) {
            Toast.makeText(
                this,
                "No Spotify client ID is set up in this build.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        awaitingBrowser = authManager.launchAuthorization(this)
        if (!awaitingBrowser) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        // El primer onResume ocurre justo después de lanzar el navegador, antes de que la
        // Custom Tab tape la pantalla. A partir del segundo, volver aquí sin callback
        // significa que el usuario se echó atrás.
        if (!firstResumeConsumed) {
            firstResumeConsumed = true
            return
        }
        if (awaitingBrowser && !completed) finish()
    }

    /** @return true si el intent traía la respuesta de Spotify y ya se está procesando. */
    private fun consumeCallback(intent: Intent?): Boolean {
        val data = intent?.data
        if (!authManager.isCallbackUri(data)) return false

        completed = true
        lifecycleScope.launch {
            val result = authManager.handleAuthorizationResponse(data!!)
            if (result.isSuccess) {
                // La importación va en un worker para que sobreviva al cierre de esta pantalla.
                SpotifySyncWorker.enqueue(applicationContext)
                Toast.makeText(
                    this@SpotifyLoginActivity,
                    "Spotify connected — importing your library.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@SpotifyLoginActivity,
                    authManager.lastError.value ?: "Spotify sign-in failed.",
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
        return true
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, SpotifyLoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
