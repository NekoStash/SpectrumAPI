package hk.uwu.spectrumapi.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hk.uwu.spectrumapi.manager.ui.ManagerApp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Entry and lifecycle bridge only; opening Manager never requests root or starts audio capture. */
class MainActivity : ComponentActivity() {
    private lateinit var controller: ManagerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = ManagerController(this)
        setContent { MiuixTheme { ManagerApp(controller) } }
        controller.observe()
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onResume() {
        super.onResume()
        controller.permissions.onResume()
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}
