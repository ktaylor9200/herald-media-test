package dev.hermeskotlin.core.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [CallAudio] on Android: while a Bluetooth headset is connected, the phone is in call mode with the headset as the
 * call's device. The recorder then listens on the call's microphone and the voice plays as a call
 * ([AndroidVoiceRecorder], [AndroidSpeechPlayer] read [onHeadset]), both on the headset. A wired or USB headset needs
 * none of this: its microphone is the phone's own whenever it's plugged in.
 */
class AndroidCallAudio(context: Context) : CallAudio {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var open = false
    private var previousMode = AudioManager.MODE_NORMAL

    /** The chat's sound is on a headset now: record and play as a call. */
    @Volatile var onHeadset = false
        private set

    /** Hears of every device there is when it's registered, and of each one that comes or goes after. */
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = reroute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = reroute()
    }

    override suspend fun open() {
        synchronized(lock) {
            if (open) return
            open = true
            audioManager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        }
        reroute()
        // The headset's link takes a moment to come up, and what's said before it does is lost.
        if (onHeadset) withTimeoutOrNull(LINK_MS) { while (!linked()) delay(POLL_MS) }
    }

    override fun close() {
        synchronized(lock) {
            if (!open) return
            open = false
            audioManager.unregisterAudioDeviceCallback(devices)
            leave()
        }
    }

    @Suppress("DEPRECATION")
    private fun reroute() {
        synchronized(lock) {
            if (!open) return
            val sco = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            val headset = if (sco) scoHeadset() else communicationHeadset()
            if (headset == null) {
                leave()
                return
            }
            if (!onHeadset) {
                previousMode = audioManager.mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                onHeadset = true
            }
            if (!sco) {
                if (audioManager.communicationDevice?.id != headset.id) audioManager.setCommunicationDevice(headset)
            } else if (!audioManager.isBluetoothScoOn) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun leave() {
        if (!onHeadset) return
        onHeadset = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
        audioManager.mode = previousMode
    }

    @Suppress("DEPRECATION")
    private fun linked(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.type in BLUETOOTH_HEADSETS
        else audioManager.isBluetoothScoOn

    @RequiresApi(Build.VERSION_CODES.S)
    private fun communicationHeadset(): AudioDeviceInfo? =
        audioManager.availableCommunicationDevices.firstOrNull { it.type in BLUETOOTH_HEADSETS }

    private fun scoHeadset(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    private companion object {
        /** Long enough for a headset's call link to come up; past it, the chat goes on rather than wait. */
        const val LINK_MS = 2_000L
        const val POLL_MS = 50L

        /** A Bluetooth headset's call link, classic or LE Audio. */
        val BLUETOOTH_HEADSETS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
