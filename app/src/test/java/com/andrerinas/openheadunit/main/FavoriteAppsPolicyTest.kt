package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteAppsPolicyTest {

    private val empty: List<FavoriteTarget> = List(FavoriteAppsPolicy.SLOT_COUNT) { FavoriteTarget.Empty }

    private fun app(name: String) = FavoriteTarget.App(name)

    private fun shortcut(packageName: String, id: String) = FavoriteTarget.Shortcut(packageName, id)

    @Test
    fun `nothing stored decodes to four empty slots`() {
        assertEquals(empty, FavoriteAppsPolicy.decode(""))
    }

    @Test
    fun `a short or overlong value still decodes to four slots`() {
        assertEquals(
            listOf(app("a"), app("b"), FavoriteTarget.Empty, FavoriteTarget.Empty),
            FavoriteAppsPolicy.decode("a,b")
        )
        assertEquals(
            listOf(app("a"), app("b"), app("c"), app("d")),
            FavoriteAppsPolicy.decode("a,b,c,d,e")
        )
    }

    @Test
    fun `a shortcut decodes to its app and id`() {
        assertEquals(
            listOf(shortcut("maps", "navigate"), FavoriteTarget.Empty, FavoriteTarget.Empty, FavoriteTarget.Empty),
            FavoriteAppsPolicy.decode("maps|navigate")
        )
    }

    @Test
    fun `an id that contains the separator keeps the rest of it`() {
        assertEquals(
            listOf(shortcut("maps", "a|b")),
            FavoriteAppsPolicy.decode("maps|a|b").take(1)
        )
    }

    @Test
    fun `a separator with nothing on one side is a package name, which resolves to nothing`() {
        assertEquals(app("|"), FavoriteAppsPolicy.decode("|").first())
        assertEquals(app("maps|"), FavoriteAppsPolicy.decode("maps|").first())
    }

    @Test
    fun `encode and decode keep empty slots, apps and shortcuts in place`() {
        val slots = listOf(
            FavoriteTarget.Empty,
            app("maps"),
            shortcut("music", "shuffle"),
            FavoriteTarget.Empty
        )
        assertEquals(slots, FavoriteAppsPolicy.decode(FavoriteAppsPolicy.encode(slots)))
    }

    @Test
    fun `placing a favorite already in another slot moves it`() {
        val slots = listOf(app("maps"), app("music"), FavoriteTarget.Empty, FavoriteTarget.Empty)
        assertEquals(
            listOf(FavoriteTarget.Empty, app("music"), app("maps"), FavoriteTarget.Empty),
            FavoriteAppsPolicy.place(slots, 2, app("maps"))
        )
    }

    @Test
    fun `placing a shortcut of an app that is already a favorite keeps both`() {
        val slots = listOf(app("maps"), FavoriteTarget.Empty, FavoriteTarget.Empty, FavoriteTarget.Empty)
        assertEquals(
            listOf(app("maps"), shortcut("maps", "navigate"), FavoriteTarget.Empty, FavoriteTarget.Empty),
            FavoriteAppsPolicy.place(slots, 1, shortcut("maps", "navigate"))
        )
    }

    @Test
    fun `adding fills the first free slot`() {
        val slots = listOf(app("maps"), FavoriteTarget.Empty, FavoriteTarget.Empty, app("music"))
        assertEquals(
            listOf(app("maps"), shortcut("radio", "scan"), FavoriteTarget.Empty, app("music")),
            FavoriteAppsPolicy.add(slots, shortcut("radio", "scan"))
        )
    }

    @Test
    fun `adding a favorite that is already there changes nothing`() {
        val slots = listOf(app("maps"), FavoriteTarget.Empty, FavoriteTarget.Empty, FavoriteTarget.Empty)
        assertEquals(slots, FavoriteAppsPolicy.add(slots, app("maps")))
    }

    @Test
    fun `adding to a full row is refused`() {
        assertNull(FavoriteAppsPolicy.add(listOf(app("a"), app("b"), app("c"), app("d")), app("e")))
    }

    @Test
    fun `removing empties only that slot`() {
        assertEquals(
            listOf(app("a"), FavoriteTarget.Empty, app("c"), app("d")),
            FavoriteAppsPolicy.remove(listOf(app("a"), app("b"), app("c"), app("d")), 1)
        )
    }
}
