package com.youfree.island

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import kotlin.math.roundToInt

/** Lê e muda o estado do aparelho: bateria, rede, brilho, volume, lanterna. */
class DeviceStatus(private val ctx: Context) {

    private val battery = ctx.getSystemService(BatteryManager::class.java)!!
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)!!
    private val telephony = ctx.getSystemService(TelephonyManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)!!
    private val camera = ctx.getSystemService(CameraManager::class.java)!!

    var torchOn = false
        private set
    private val torchId: String? = try {
        camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: CameraAccessException) {
        null
    }
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) torchOn = enabled
        }
    }

    init {
        camera.registerTorchCallback(torchCallback, IslandHub.main)
    }

    fun release() {
        camera.unregisterTorchCallback(torchCallback)
    }

    // ----- Bateria -----

    fun batteryPercent(): Int = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun isCharging(): Boolean = battery.isCharging

    // ----- Rede -----

    private fun capabilities(): NetworkCapabilities? = try {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    } catch (_: SecurityException) {
        null
    }

    fun isOnline(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun isWifi(): Boolean = capabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    fun isMobileData(): Boolean = capabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    /** 0 a 4 barras, ou -1 se não der para saber. */
    fun signalLevel(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
        return try {
            telephony?.signalStrength?.level ?: -1
        } catch (_: SecurityException) {
            -1
        }
    }

    fun carrierName(): String = telephony?.networkOperatorName.orEmpty()

    // ----- Brilho -----

    fun canChangeBrightness(): Boolean = Settings.System.canWrite(ctx)

    fun brightnessPercent(): Int {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return (raw * 100f / 255f).roundToInt().coerceIn(0, 100)
    }

    fun isAutoBrightness(): Boolean = Settings.System.getInt(
        ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    /** Muda o brilho (desliga o automático). Retorna false se falta permissão. */
    fun setBrightnessPercent(percent: Int): Boolean {
        if (!canChangeBrightness()) return false
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (percent.coerceIn(1, 100) * 255 / 100))
        return true
    }

    fun brightnessPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ----- Som -----

    fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolumePercent(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, 0)
    }

    fun isVibrateMode(): Boolean = audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE

    /** Alterna entre vibrar e som normal. Retorna false se o sistema não deixou. */
    fun setVibrateMode(vibrate: Boolean): Boolean = try {
        audio.ringerMode = if (vibrate) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
        true
    } catch (_: SecurityException) {
        false
    }

    // ----- Lanterna -----

    fun hasTorch(): Boolean = torchId != null

    fun setTorch(on: Boolean): Boolean {
        val id = torchId ?: return false
        return try {
            camera.setTorchMode(id, on)
            torchOn = on
            true
        } catch (_: CameraAccessException) {
            false
        }
    }

    // ----- Painéis do sistema -----

    fun wifiPanelIntent(): Intent = panelIntent(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS,
    )

    fun internetPanelIntent(): Intent = panelIntent(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS,
    )

    fun bluetoothIntent(): Intent = panelIntent(Settings.ACTION_BLUETOOTH_SETTINGS)

    private fun panelIntent(action: String) = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
