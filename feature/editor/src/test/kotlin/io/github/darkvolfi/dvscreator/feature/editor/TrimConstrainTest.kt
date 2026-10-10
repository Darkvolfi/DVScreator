package io.github.darkvolfi.dvscreator.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimConstrainTest {

    @Test
    fun `bitiş çok ileri sürüklenince başlangıç takip eder`() {
        assertEquals(5_000f..15_000f, constrain(0f..10_000f, 0f..15_000f, maxMs = 10_000f, minMs = 500f))
    }

    @Test
    fun `başlangıç çok geri sürüklenince bitiş takip eder`() {
        assertEquals(2_000f..12_000f, constrain(5_000f..15_000f, 2_000f..15_000f, maxMs = 10_000f, minMs = 500f))
    }

    @Test
    fun `en kısa uzunluğun altına inilmez`() {
        assertEquals(4_500f..5_000f, constrain(1_000f..5_000f, 4_900f..5_000f, maxMs = 10_000f, minMs = 500f))
        assertEquals(1_000f..1_500f, constrain(1_000f..5_000f, 1_000f..1_100f, maxMs = 10_000f, minMs = 500f))
    }

    @Test
    fun `sınır içindeki seçim değişmez`() {
        assertEquals(1_000f..4_000f, constrain(0f..4_000f, 1_000f..4_000f, maxMs = 10_000f, minMs = 500f))
    }
}
