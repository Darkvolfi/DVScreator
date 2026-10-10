package io.github.darkvolfi.dvscreator.feature.packs

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitForPacksTest {
    private fun sizes(n: Int) = PacksViewModel.splitForPacks((1..n).toList()).map { it.size }

    @Test
    fun `30 ve altı tek paket`() {
        assertEquals(listOf(12), sizes(12))
        assertEquals(listOf(30), sizes(30))
    }

    @Test
    fun `fazlası eşit bölünür, hiçbir parça 3ten az kalmaz`() {
        assertEquals(listOf(16, 15), sizes(31))
        assertEquals(listOf(23, 22), sizes(45))
        assertEquals(listOf(30, 30), sizes(60))
        assertEquals(listOf(21, 20, 20), sizes(61))
    }

    @Test
    fun `sıra korunur`() {
        val parts = PacksViewModel.splitForPacks((1..31).toList())
        assertEquals((1..31).toList(), parts.flatten())
    }
}
