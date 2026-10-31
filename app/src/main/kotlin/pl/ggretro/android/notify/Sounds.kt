package pl.ggretro.android.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import pl.ggretro.android.R

/** Original GG 6.1 sounds: wiadomosc.wav (message) and dostepny.wav (contact came online). */
class Sounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val message = pool.load(context, R.raw.gg_wiadomosc, 1)
    private val online = pool.load(context, R.raw.gg_dostepny, 1)

    fun playMessage() {
        pool.play(message, 1f, 1f, 1, 0, 1f)
    }

    fun playOnline() {
        pool.play(online, 1f, 1f, 1, 0, 1f)
    }
}
