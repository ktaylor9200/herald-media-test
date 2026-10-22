package dev.hermeskotlin.core.voice

/**
 * A voice chat's sound on the Bluetooth headset, the way a call's is: a headset (a car's hands-free too) only lends
 * its microphone to a call, so without this the phone's own microphone listens while the voice plays in your ear.
 * On Android, call mode with the headset as the call's device.
 */
interface CallAudio {
    /**
     * Puts the chat's sound on a connected headset, once it's ready to hear, and keeps it there until [close]: one
     * connected meanwhile takes over, and one that goes hands back to the phone. Without a headset nothing changes.
     */
    suspend fun open()

    fun close()

    /** A device without call routing: the chat's sound goes where the phone sends it. */
    object None : CallAudio {
        override suspend fun open() = Unit
        override fun close() = Unit
    }
}
