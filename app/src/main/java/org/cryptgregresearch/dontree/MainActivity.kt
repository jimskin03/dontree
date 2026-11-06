package org.cryptgregresearch.dontree

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.compose.runtime.mutableStateOf
import org.cryptgregresearch.dontree.ui.Dontree

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model: PlayerViewModel by viewModels()
    private val granted = mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted.value = allowed
        if (allowed) model.refresh()
        else model.message.value = "Music access is needed to show files on this device. You can enable it in app settings."
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importModel) }
    private val lyricsPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val song = model.pendingLyricsTarget; model.pendingLyricsTarget = null
        if (uri != null && song != null) model.importLyrics(uri, song)
    }
    private val audioPermission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Dontree(model, granted.value,
                onPermission = { permission.launch(audioPermission) },
                onAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) },
                onModelImport = { modelPicker.launch(arrayOf("*/*")) },
                onLyricsImport = { model.currentSong()?.let { model.pendingLyricsTarget = it; lyricsPicker.launch(arrayOf("*/*")) } })
        }
    }
    override fun onResume() {
        super.onResume()
        granted.value = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (granted.value) model.refresh() else model.stopAgent()
    }
}
