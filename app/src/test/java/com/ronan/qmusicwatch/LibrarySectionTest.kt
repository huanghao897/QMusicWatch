package com.ronan.qmusicwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySectionTest {
    @Test fun mapsEachLibraryRouteToItsOwnSection() {
        assertEquals(LibrarySection.Liked, LibrarySection.fromRoute("liked"))
        assertEquals(LibrarySection.Created, LibrarySection.fromRoute("created"))
        assertEquals(LibrarySection.Collected, LibrarySection.fromRoute("collected"))
        assertEquals(LibrarySection.Liked, LibrarySection.fromRoute("unknown"))
    }
}
