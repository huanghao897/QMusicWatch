package com.ronan.qmusicwatch

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], qualifiers = "w480dp-h480dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WatchActivityRobolectricTest {
    @Test fun manifestDeclaresNotificationPermissionForMediaControls() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val permissions = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.REQUEST_INSTALL_PACKAGES in permissions)
        controller.destroy()
    }

    @Test fun mainActivityCreatesAndLaysOutOnA480SquareDisplay() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertFalse(activity.isFinishing)
            assertTrue("Compose root was not attached", content.childCount > 0)
            assertEquals(480, activity.resources.displayMetrics.widthPixels)
            assertEquals(480, activity.resources.displayMetrics.heightPixels)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
