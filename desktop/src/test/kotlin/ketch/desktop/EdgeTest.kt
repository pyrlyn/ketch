package ketch.desktop

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class EdgeTest {
    private val start = Rectangle(100, 200, 1000, 800)
    private val least = Dimension(900, 600)

    @Test
    fun theFarSidesStayPut() {
        assertEquals(Rectangle(100, 200, 1050, 800), Edge.Right.resized(start, 50, 30, least))
        assertEquals(Rectangle(100, 200, 1000, 830), Edge.Bottom.resized(start, 50, 30, least))
        assertEquals(Rectangle(50, 200, 1050, 800), Edge.Left.resized(start, -50, 30, least))
        assertEquals(Rectangle(100, 170, 1000, 830), Edge.Top.resized(start, 50, -30, least))
    }

    @Test
    fun aCornerMovesBothOfItsSides() {
        assertEquals(Rectangle(90, 190, 1010, 810), Edge.TopLeft.resized(start, -10, -10, least))
        assertEquals(Rectangle(100, 200, 1010, 810), Edge.BottomRight.resized(start, 10, 10, least))
    }

    @Test
    fun aWindowStopsAtItsLeastSizeWithoutSliding() {
        assertEquals(Rectangle(200, 400, 900, 600), Edge.TopLeft.resized(start, 500, 500, least))
        assertEquals(Rectangle(100, 200, 900, 600), Edge.BottomRight.resized(start, -500, -500, least))
    }
}
