package pl.ggretro.protocol

/**
 * The 32-bit password hash used by GG_LOGIN60 (GG 6.0 and older).
 *
 * The gg-retro server hashes the UTF-8 bytes of the stored password, so we do the same
 * (identical to CP1250 for ASCII passwords).
 */
object GgHash {
    fun loginHash(password: String, seed: Int): Int {
        var x = 0
        var y = seed
        for (b in password.toByteArray(Charsets.UTF_8)) {
            x = (x and 0xffffff00.toInt()) or (b.toInt() and 0xff)
            y = y xor x
            y += x
            x = x shl 8
            y = y xor x
            x = x shl 8
            y -= x
            x = x shl 8
            y = y xor x
            val z = y and 0x1f
            y = Integer.rotateLeft(y, z)
        }
        return y
    }
}
