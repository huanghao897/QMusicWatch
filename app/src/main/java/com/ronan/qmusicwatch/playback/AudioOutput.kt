package com.ronan.qmusicwatch.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

fun hasPrivateAudioOutput(context: Context): Boolean {
    val manager = context.getSystemService(AudioManager::class.java)
    val supported = mutableSetOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    ).apply { if (android.os.Build.VERSION.SDK_INT >= 26) add(AudioDeviceInfo.TYPE_USB_HEADSET) }
    return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in supported }
}
