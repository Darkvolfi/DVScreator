package io.github.darkvolfi.dvscreator.core.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerImportTest {

    private fun webpWithExif(json: String): ByteArray =
        byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0) + "WEBPVP8X....EXIF..".toByteArray() + json.toByteArray(Charsets.UTF_8) + byteArrayOf(0, 1, 2)

    @Test
    fun `WhatsApp EXIF içindeki emojiler okunur`() {
        val bytes = webpWithExif("""{"sticker-pack-id":"abc","sticker-pack-name":"x","emojis":["😂","❤️"]}""")
        assertEquals(listOf("😂", "❤️"), StickerImport.emojisOf(bytes))
    }

    @Test
    fun `en fazla 3 emoji alınır`() {
        val bytes = webpWithExif("""{"emojis":["😀","😂","🥰","😎"]}""")
        assertEquals(3, StickerImport.emojisOf(bytes).size)
    }

    @Test
    fun `emoji yoksa boş liste`() {
        assertEquals(emptyList<String>(), StickerImport.emojisOf(webpWithExif("""{"sticker-pack-id":"abc"}""")))
        assertEquals(emptyList<String>(), StickerImport.emojisOf(byteArrayOf(1, 2, 3)))
    }
}

class ReceivedPackInfoTest {
    @Test
    fun `EXIF içindeki paket bilgisi okunur`() {
        val tail = "....EXIF..".toByteArray() +
            """{"sticker-pack-id":"com.example.cats","sticker-pack-name":"Kediler \"sevimli\"","sticker-pack-publisher":"Ayşe","emojis":["😺"]}""".toByteArray()
        val info = ReceivedStickers.parsePackInfo(tail)!!
        assertEquals("com.example.cats", info.id)
        assertEquals("Kediler \"sevimli\"", info.name)
        assertEquals("Ayşe", info.publisher)
    }

    @Test
    fun `paket bilgisi yoksa null`() {
        assertEquals(null, ReceivedStickers.parsePackInfo("""{"emojis":["😀"]}""".toByteArray()))
    }
}
