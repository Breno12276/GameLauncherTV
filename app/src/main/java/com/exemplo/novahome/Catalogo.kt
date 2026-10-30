package com.exemplo.novahome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

enum class Categoria { VIDEO, MUSICA, JOGO, OUTRO }

class AppInfo(
    val pacote: String,
    val componente: ComponentName,
    val nome: String,
    val categoria: Categoria,
    val cor: Int,
    val icone: Bitmap?,
    /** Banner de Android TV que o próprio app traz (16:9), se tiver. */
    val banner: Bitmap? = null,
)

/** Lê os apps instalados (fora da linha principal) e decide a ordem e a cor de cada um. */
object Catalogo {

    private val NETFLIX = setOf("com.netflix.mediaclient", "com.netflix.ninja")
    private val PRIME = setOf("com.amazon.avod.thirdpartyclient", "com.amazon.amazonvideo.livingroom", "com.amazon.avod")
    private val YOUTUBE = setOf(
        "com.google.android.youtube", "com.google.android.youtube.tv", "com.google.android.apps.youtube.music",
        "org.smarttube.stable", "org.smarttube.beta", "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta",
    )
    private val DISNEY = setOf("com.disney.disneyplus")
    private val MAX = setOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.max")
    private val GLOBOPLAY = setOf("com.globo.globotv")
    private val SPOTIFY = setOf("com.spotify.music", "com.spotify.tv.android")
    private val APPLE_TV = setOf("com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv")
    private val PARAMOUNT = setOf("com.cbs.app", "com.cbs.ca")
    private val CRUNCHYROLL = setOf("com.crunchyroll.crunchyroid")

    /** Ordem e cores da referência. */
    private val MARCAS: List<Pair<Set<String>, Int>> = listOf(
        NETFLIX to 0xFFE50914.toInt(),
        PRIME to 0xFF1A98FF.toInt(),
        YOUTUBE to 0xFFFF1E3C.toInt(),
        DISNEY to 0xFF2E6BFF.toInt(),
        MAX to 0xFF5B4BFF.toInt(),
        GLOBOPLAY to 0xFFFF5A1F.toInt(),
        SPOTIFY to 0xFF1ED760.toInt(),
        APPLE_TV to 0xFFB8C0CC.toInt(),
        PARAMOUNT to 0xFF2F7BFF.toInt(),
        CRUNCHYROLL to 0xFFFF7A1A.toInt(),
    )

    private val OUTROS_VIDEO = setOf(
        "com.google.android.videos", "com.samsung.android.video", "com.world.youcinetv", "tv.twitch.android.app",
        "org.xbmc.kodi", "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.plexapp.android",
        "tv.pluto.android", "com.stremio.one",
    )
    private val MUSICA = SPOTIFY + setOf(
        "com.google.android.apps.youtube.music", "com.deezer.android.app", "com.amazon.mp3", "com.aspiro.tidal",
        "com.soundcloud.android", "com.sec.android.app.music", "com.apple.android.music",
    )

    /** Emuladores e frontends de jogos (muitos não se declaram como jogo). */
    private val EMULADORES = setOf(
        "com.retroarch", "com.retroarch.aarch64", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold",
        "org.dolphinemu.dolphinemu", "com.github.stenzek.duckstation", "xyz.aethersx2.android",
        "org.citra.citra_emu", "io.github.lime3ds.android", "me.magnum.melonds", "com.dsemu.drastic",
        "org.devmiyax.yabasanshioro2", "org.uoyabause.uranus", "com.xiaoji.egggame",
        "com.miHoYo.Yuanshen.nightly", "dev.eden.eden_emulator",
        "org.yuzu.yuzu_emu", "skyline.emu", "com.winlator", "com.magneticchen.daijishou",
        "com.iisulauncher", "com.radikal.gamelauncher", "studio.deimos.pinyonshift",
    )
    // com.gow3.*: God of War III (jogo empacotado como app), não emulador.
    private val JOGOS = EMULADORES + setOf("com.samsung.android.game.gamehome", "com.gow3.android", "com.gow3.android.bionic")

    fun ehEmulador(pacote: String) = pacote in EMULADORES

    /** Serviço de vídeo → nome e código do provedor no TMDB (região BR). */
    private val PROVEDORES: List<Pair<Set<String>, Pair<String, Int>>> = listOf(
        NETFLIX to ("Netflix" to 8),
        PRIME to ("Prime Video" to 119),
        DISNEY to ("Disney+" to 337),
        MAX to ("Max" to 1899),
        GLOBOPLAY to ("Globoplay" to 307),
        APPLE_TV to ("Apple TV+" to 350),
        PARAMOUNT to ("Paramount+" to 531),
        CRUNCHYROLL to ("Crunchyroll" to 283),
        setOf("com.google.android.videos") to ("Google TV" to 3),
    )

    fun provedor(pacote: String) = PROVEDORES.firstOrNull { pacote in it.first }?.second

    fun carregar(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val tamanho = 160
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .asSequence()
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val pacote = ai.packageName
                val icone = try { paraBitmap(ri.loadIcon(pm), tamanho) } catch (_: Exception) { null }
                AppInfo(
                    pacote = pacote,
                    componente = ComponentName(pacote, ai.name),
                    nome = ri.loadLabel(pm).toString(),
                    categoria = categoria(pacote, ai.applicationInfo.category),
                    cor = corMarca(pacote) ?: icone?.let { corMedia(it) } ?: Paleta.AZUL,
                    icone = icone,
                    banner = bannerDeTv(pm, ri.activityInfo),
                )
            }
            .sortedBy { it.nome.lowercase() }
            .toList()
    }

    /**
     * Banner de TV que o app declara para o Android TV (android:banner): na tela do celular, na
     * aplicação ou na tela de TV (LEANBACK_LAUNCHER). Desenhado em 400×225, a proporção da TV.
     */
    private fun bannerDeTv(pm: PackageManager, ai: android.content.pm.ActivityInfo): Bitmap? = try {
        val d = ai.loadBanner(pm)
            ?: ai.applicationInfo.loadBanner(pm)
            ?: pm.getLeanbackLaunchIntentForPackage(ai.packageName)?.component?.let { pm.getActivityBanner(it) }
        d?.let {
            val b = Bitmap.createBitmap(400, 225, Bitmap.Config.ARGB_8888)
            it.setBounds(0, 0, 400, 225)
            it.draw(Canvas(b))
            b
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Favoritos de fábrica: vídeo e música (na ordem das marcas) e as lojas. A pessoa adiciona,
     * tira e reordena o resto pela tela; jogos têm fileira própria.
     */
    fun favoritos(lista: List<AppInfo>): List<AppInfo> {
        val midia = lista.filter { it.categoria == Categoria.VIDEO || it.categoria == Categoria.MUSICA }
            .sortedWith(compareBy({ ordemMarca(it.pacote) }, { it.nome.lowercase() }))
        val lojas = lista.filter { prioridade(it) < 2 }.sortedBy { prioridade(it) }
        return midia + lojas
    }

    /** Nome com que o app aparece no SteamGridDB (banners de apps). */
    private val NA_LOJA_DE_ARTES: List<Pair<Set<String>, String>> = listOf(
        NETFLIX to "Netflix",
        // Só o YouTube oficial: o SmartTube com a mesma arte ficaria idêntico ao lado dele.
        setOf("com.google.android.youtube", "com.google.android.youtube.tv") to "YouTube",
        setOf("com.google.android.apps.youtube.music") to "YouTube Music",
        PRIME to "Amazon Prime Video",
        SPOTIFY to "Spotify",
        DISNEY to "Disney+",
        MAX to "Max",
        GLOBOPLAY to "Globoplay",
        CRUNCHYROLL to "Crunchyroll",
        setOf("tv.twitch.android.app") to "Twitch",
        setOf("com.google.android.videos") to "Google Play Movies & TV",
        setOf("com.android.vending") to "Google Play Store",
    )

    fun nomeParaArte(app: AppInfo) = NA_LOJA_DE_ARTES.firstOrNull { app.pacote in it.first }?.second ?: app.nome

    fun daCategoria(lista: List<AppInfo>, cat: Categoria) =
        lista.filter { it.categoria == cat }.sortedWith(compareBy({ ordemMarca(it.pacote) }, { it.nome.lowercase() }))

    private fun ordemMarca(pacote: String): Int {
        val i = MARCAS.indexOfFirst { pacote in it.first }
        return if (i >= 0) i else 100
    }

    private fun prioridade(app: AppInfo) = when (app.pacote) {
        "com.android.vending" -> 0
        "com.sec.android.app.samsungapps" -> 1
        else -> 2
    }

    private fun categoria(pacote: String, cat: Int) = when {
        pacote in JOGOS -> Categoria.JOGO
        pacote in MUSICA || cat == ApplicationInfo.CATEGORY_AUDIO -> Categoria.MUSICA
        MARCAS.any { pacote in it.first } || pacote in OUTROS_VIDEO || cat == ApplicationInfo.CATEGORY_VIDEO -> Categoria.VIDEO
        cat == ApplicationInfo.CATEGORY_GAME -> Categoria.JOGO
        else -> Categoria.OUTRO
    }

    private fun corMarca(pacote: String) = MARCAS.firstOrNull { pacote in it.first }?.second

    /**
     * Para apps desconhecidos: a cor viva dominante do ícone. Média simples de ícones
     * multicoloridos (Google TV, Play Store) dava um verde-oliva sem graça.
     */
    private fun corMedia(bmp: Bitmap): Int {
        val p = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val faixas = 12
        val r = LongArray(faixas)
        val g = LongArray(faixas)
        val b = LongArray(faixas)
        val n = IntArray(faixas)
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = p.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue
            val f = (hsv[0] / 360f * faixas).toInt().coerceIn(0, faixas - 1)
            r[f] += Color.red(c); g[f] += Color.green(c); b[f] += Color.blue(c); n[f]++
        }
        if (p !== bmp) p.recycle()
        val f = n.indices.maxByOrNull { n[it] } ?: return Paleta.AZUL
        if (n[f] < 8) return Paleta.AZUL
        Color.colorToHSV(Color.rgb((r[f] / n[f]).toInt(), (g[f] / n[f]).toInt(), (b[f] / n[f]).toInt()), hsv)
        hsv[1] = maxOf(hsv[1], 0.55f)
        hsv[2] = maxOf(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }

    private fun paraBitmap(d: Drawable, tamanho: Int): Bitmap {
        val b = Bitmap.createBitmap(tamanho, tamanho, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, tamanho, tamanho)
        d.draw(Canvas(b))
        return b
    }
}
