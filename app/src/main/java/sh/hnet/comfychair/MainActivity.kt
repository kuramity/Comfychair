package sh.hnet.comfychair

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.hnet.comfychair.storage.PreferencesMaintenance
import sh.hnet.comfychair.ui.screens.LoginScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme

/**
 * MainActivity - The login/connection screen
 *
 * This activity handles the initial connection to the ComfyUI server.
 * Users enter the hostname and port, then click Connect to test the connection.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Run preferences maintenance in background to clean up stale entries
        // 启动时拿不到「用户显式删除服务器」的证据，按非显式处理（fail-closed）
        lifecycleScope.launch(Dispatchers.IO) {
            PreferencesMaintenance.performMaintenance(applicationContext, explicitDeletion = false)
        }

        setContent {
            ComfyChairTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LoginScreen()
                }
            }
        }
    }
}
