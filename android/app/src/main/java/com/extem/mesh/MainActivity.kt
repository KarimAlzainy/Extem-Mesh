package com.extem.mesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.extem.mesh.core.EventLog
import com.extem.mesh.core.MeshRepository
import com.extem.mesh.core.NodeIdentity
import com.extem.mesh.service.MeshService
import com.extem.mesh.ui.ExtemTheme
import com.extem.mesh.ui.MeshScreen

/**
 * The only activity. It asks for the Bluetooth permissions and hosts the Compose screen.
 * It never touches Bluetooth itself: all radio work lives in [MeshService].
 */
class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsGranted = hasAllPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        // Show the node ID immediately, even before the mesh is started.
        MeshRepository.setSelfId(NodeIdentity.getOrCreate(this))
        permissionsGranted = hasAllPermissions()

        setContent {
            ExtemTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MeshScreen(
                        permissionsGranted = permissionsGranted,
                        onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                        onStart = { MeshService.start(this) },
                        onStop = { MeshService.stop(this) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted or revoked permissions in Settings.
        permissionsGranted = hasAllPermissions()
    }

    /**
     * Android 12 (API 31) split Bluetooth into three runtime permissions.
     * Before that, scanning needed location instead.
     */
    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
