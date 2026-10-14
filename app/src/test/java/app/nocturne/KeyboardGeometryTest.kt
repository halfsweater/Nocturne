package app.nocturne

import app.nocturne.ui.keyboard.KeyboardGeometry
import app.nocturne.ui.keyboard.Piano
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGeometryTest {

    @Test
    fun eightyEightKeysWithFiftyTwoWhite() {
        assertEquals(52, Piano.WHITE_COUNT)
        assertEquals(21, Piano.WHITE_NOTES.first())
        assertEquals(108, Piano.WHITE_NOTES.last())
        assertEquals(36, (Piano.LOWEST..Piano.HIGHEST).count { Piano.isBlack(it) })
    }

    @Test
    fun hitTestingWhiteAndBlackKeys() {
        // 7 visible white keys starting at C4 (white index 23) -> C4..B4
        val g = KeyboardGeometry(700f, 300f, firstWhite = 23f, visibleWhite = 7)
        assertEquals(60, g.noteAt(50f, 280f))            // C4, lower part of the key
        assertEquals(62, g.noteAt(150f, 280f))           // D4
        assertEquals(61, g.noteAt(100f - 8f, 50f))       // C#4 sits on the C/D boundary
        assertEquals(60, g.noteAt(30f, 50f))             // upper part of C4 away from the black key
        assertEquals(71, g.noteAt(690f, 290f))           // B4
        assertEquals(-1, g.noteAt(50f, 400f))            // outside the view
    }

    @Test
    fun everyBlackKeyIsReachable() {
        val g = KeyboardGeometry(5200f, 300f, firstWhite = 0f, visibleWhite = 52)
        for (n in Piano.LOWEST..Piano.HIGHEST) {
            if (!Piano.isBlack(n)) continue
            val x = g.blackLeft(n) + g.blackWidth / 2
            assertEquals("black key $n", n, g.noteAt(x, 20f))
        }
    }

    @Test
    fun velocityGrowsTowardTheFrontOfTheKey() {
        val g = KeyboardGeometry(700f, 300f, 23f, 7)
        val back = g.velocityAt(60, 20f, 0f, false)
        val front = g.velocityAt(60, 295f, 0f, false)
        assertTrue(front > back)
        assertTrue(back >= 30 && front <= 127)
    }

    @Test
    fun noteNames() {
        assertEquals("C4", Piano.name(60))
        assertEquals("A0", Piano.name(21))
        assertEquals("C8", Piano.name(108))
    }
}
