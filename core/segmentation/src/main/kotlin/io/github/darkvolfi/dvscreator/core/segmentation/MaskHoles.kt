package io.github.darkvolfi.dvscreator.core.segmentation

/**
 * Maskedeki delikleri doldurur. Genel model çizimlerde (beyaz dolgulu, siyah çizgili karakterler)
 * yalnızca çizgileri ön plan sayıp içini arka plan bırakıyor — kullanıcı "içini siliyor" dedi (2026-10-10).
 *
 * Gerçek arka plan görüntünün kenarına bağlıdır: üst, sol ve sağ kenardan arka plan pikselleri
 * (4-komşulukla — çapraz boşluklardan sızmasın) taşırılır; ulaşılamayan arka plan = delik → ön plan.
 * Alt kenardan başlanmaz: konu çoğu zaman alttan kesiktir (gövde, büst) ve içi alt kenara değer.
 */
internal object MaskHoles {

    /** [mask] yerinde değişir: kenardan ulaşılamayan (ve ulaşılabilir arka plana [EDGE_BAND] pikselden uzak) değerler [foreground] olur. */
    fun fill(mask: FloatArray, width: Int, height: Int, threshold: Float, foreground: Float, edgeBand: Int = EDGE_BAND) {
        val reached = BooleanArray(width * height)
        val stack = IntArray(width * height)
        var top = 0
        fun seed(i: Int) {
            if (!reached[i] && mask[i] < threshold) { reached[i] = true; stack[top++] = i }
        }
        for (x in 0 until width) seed(x)
        for (y in 0 until height) { seed(y * width); seed(y * width + width - 1) }
        while (top > 0) {
            val i = stack[--top]
            val x = i % width
            val y = i / width
            if (x > 0) seed(i - 1)
            if (x < width - 1) seed(i + 1)
            if (y > 0) seed(i - width)
            if (y < height - 1) seed(i + width)
        }
        // Arka plana [edgeBand] pikselden uzak her şey tam ön plan: delikler ve çizgi-dolgu arasındaki yarı
        // saydam izler de kapanır; yalnızca dış kenarın yumuşak geçişi (kenar yumuşatma) korunur
        var near = reached
        repeat(edgeBand) {
            val grown = near.copyOf()
            for (i in near.indices) if (near[i]) {
                val x = i % width
                if (x > 0) grown[i - 1] = true
                if (x < width - 1) grown[i + 1] = true
                if (i >= width) grown[i - width] = true
                if (i + width < near.size) grown[i + width] = true
            }
            near = grown
        }
        for (i in mask.indices) if (!near[i]) mask[i] = foreground
    }

    private const val EDGE_BAND = 2
}
