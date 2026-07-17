package com.ronan.qmusicwatch

import androidx.media3.session.MediaSession
import com.ronan.qmusicwatch.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackServiceRobolectricTest {
    @Test fun mediaSessionPublishesPreviousAndNextNotificationButtons() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            val field = PlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }
            val session = field.get(service) as MediaSession
            val actions = session.mediaButtonPreferences.map { it.sessionCommand?.customAction }
            assertEquals(2, actions.size)
            assertTrue(actions.any { it?.endsWith(".PREVIOUS") == true })
            assertTrue(actions.any { it?.endsWith(".NEXT") == true })
        } finally {
            controller.destroy()
        }
    }
}
