package pl.ggretro.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import pl.ggretro.android.data.GgDatabase
import pl.ggretro.android.data.GgManager
import pl.ggretro.android.data.ImageStore
import pl.ggretro.android.data.SettingsStore
import pl.ggretro.android.notify.Notifier
import pl.ggretro.android.notify.Sounds
import pl.ggretro.android.ui.emots.Emoticons
import pl.ggretro.android.ui.skin.Skin

class GgApp : Application(), ImageLoaderFactory {
    lateinit var db: GgDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var images: ImageStore
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var manager: GgManager
        private set

    val emoticons: Emoticons by lazy { Emoticons.load(this) }
    val skin: Skin by lazy { Skin.load(this) }

    override fun onCreate() {
        super.onCreate()
        db = GgDatabase.create(this)
        settings = SettingsStore(this)
        images = ImageStore(this)
        notifier = Notifier(this).apply { createChannels() }
        manager = GgManager(this, db, settings, images, notifier, Sounds(this))
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = manager.networkAvailable()
            },
        )
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(false)
        .build()
}

val Context.app: GgApp get() = applicationContext as GgApp
