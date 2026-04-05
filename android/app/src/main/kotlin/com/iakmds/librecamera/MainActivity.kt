package com.iakmds.librecamera

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    companion object {
        private const val MEDIA_STORE_CHANNEL = "media_store"
        private const val OACP_CHANNEL = "com.iakmds.librecamera/oacp"
        private const val METHOD_HANDLE_OACP_COMMAND = "handleOacpCommand"
    }

    private var oacpChannel: MethodChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide only the status bar, keep navigation bar visible
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Register volume button plugin
        flutterEngine.plugins.add(VolumeButtonPlugin())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, MEDIA_STORE_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "updateItem" -> {
                        updateItem(call.argument("path")!!)
                        result.success(null)
                    }
                    "openItem" -> {
                        openItem(
                            call.argument("path")!!,
                            call.argument("mimeType")!!,
                            call.argument("openInGallery")!!
                        )
                        result.success(null)
                    }
                    "disableIntentCamera" -> {
                        disableIntentCamera(call.argument("disable")!!)
                        result.success(null)
                    }
                    "shutterSound" -> {
                        shutterSound()
                        result.success(null)
                    }
                    "startVideoSound" -> {
                        startVideoSound()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        oacpChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            OACP_CHANNEL
        )
        dispatchOacpIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchOacpIntent(intent)
    }

    private fun dispatchOacpIntent(intent: Intent?) {
        val payload = buildOacpPayload(intent) ?: return
        Log.d("OacpCamera", "Dispatching OACP command: $payload")
        sendOacpCommandWithRetry(payload, retriesLeft = 40)
        // Clear action so it doesn't re-fire on config changes
        intent?.action = null
    }

    private fun buildOacpPayload(intent: Intent?): HashMap<String, Any>? {
        val action = intent?.action ?: return null
        val payload = hashMapOf<String, Any>(
            "requestId" to System.currentTimeMillis().toString()
        )

        when {
            action.endsWith(".oacp.ACTION_TAKE_PHOTO_FRONT_CAMERA") -> {
                payload["command"] = "take_photo"
                payload["camera"] = "front"
            }
            action.endsWith(".oacp.ACTION_TAKE_PHOTO_REAR_CAMERA") -> {
                payload["command"] = "take_photo"
                payload["camera"] = "rear"
            }
            action.endsWith(".oacp.ACTION_START_VIDEO_RECORDING_FRONT_CAMERA") -> {
                payload["command"] = "start_video_recording"
                payload["camera"] = "front"
            }
            action.endsWith(".oacp.ACTION_START_VIDEO_RECORDING_REAR_CAMERA") -> {
                payload["command"] = "start_video_recording"
                payload["camera"] = "rear"
            }
            else -> return null
        }

        val durationSeconds = intent.extras?.get("EXTRA_DURATION_SECONDS")
        if (durationSeconds is Int) {
            payload["duration_seconds"] = durationSeconds
        } else if (durationSeconds is Long) {
            payload["duration_seconds"] = durationSeconds.toInt()
        }

        return payload
    }

    private fun sendOacpCommandWithRetry(payload: HashMap<String, Any>, retriesLeft: Int) {
        val channel = oacpChannel ?: run {
            Log.w("OacpCamera", "Channel is null, retries=$retriesLeft")
            return
        }
        Log.d("OacpCamera", "Sending OACP command, retries=$retriesLeft")
        channel.invokeMethod(METHOD_HANDLE_OACP_COMMAND, payload, object : MethodChannel.Result {
            override fun success(result: Any?) {
                Log.d("OacpCamera", "OACP command delivered successfully")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                Log.w("OacpCamera", "OACP error: $errorCode $errorMessage, retries=$retriesLeft")
                if (retriesLeft > 0) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendOacpCommandWithRetry(payload, retriesLeft - 1)
                    }, 250)
                } else {
                    Log.e("OacpCamera", "OACP command delivery FAILED after all retries")
                }
            }

            override fun notImplemented() {
                Log.w("OacpCamera", "OACP notImplemented (Dart not ready), retries=$retriesLeft")
                if (retriesLeft > 0) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendOacpCommandWithRetry(payload, retriesLeft - 1)
                    }, 250)
                } else {
                    Log.e("OacpCamera", "OACP command delivery FAILED - Dart never became ready")
                }
            }
        })
    }

    private fun updateItem(path: String) {
        val file = File(path)
        MediaScannerConnection.scanFile(context, arrayOf(file.toString()), null, null)
    }

    private fun openItem(path: String, mimeType: String, openInGallery: Boolean) {
        val file = File(path)
        var uri = FileProvider.getUriForFile(context, "com.iakmds.librecamera.provider", file)
        if (openInGallery) {
            uri = Uri.parse("content:/$path")
        }

        context.grantUriPermission(
                "com.iakmds.librecamera",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        Intent().apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, mimeType)

            addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            startActivity(this)
        }
    }

    private fun disableIntentCamera(disable: Boolean) {
        if (disable) {
            val pm = getApplicationContext().getPackageManager()
            val compName = ComponentName(getPackageName(), "com.iakmds.librecamera.MainActivity")
            pm.setComponentEnabledSetting(
                    compName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            )
        } else {
            val pm = getApplicationContext().getPackageManager()
            val compName = ComponentName(getPackageName(), "com.iakmds.librecamera.MainActivity")
            pm.setComponentEnabledSetting(
                    compName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun shutterSound() {
        val sound = MediaActionSound()
        sound.play(MediaActionSound.SHUTTER_CLICK)
    }

    private fun startVideoSound() {
        val sound = MediaActionSound()
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                VolumeButtonPlugin.sendVolumeEvent(true)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                VolumeButtonPlugin.sendVolumeEvent(false)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

class OpenTileService : TileService() {
    override fun onClick() {
        super.onClick()

        try {
            val intent = FlutterActivity.withNewEngine().build(this)
            if (Build.VERSION.SDK_INT >= 28) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } catch (e: Exception) {
            Log.d("debug", "Exception ${e.toString()}")
        }
    }
}
