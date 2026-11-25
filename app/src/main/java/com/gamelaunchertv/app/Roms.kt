package com.gamelaunchertv.app

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** [anos]: época do console, para a capa escolher a versão certa ("God of War" de 2005, não o de 2018). */
enum class Sistema(val nome: String, val cor: Int, val extensoes: Set<String>, val anos: IntRange) {
    PS2("PlayStation 2", 0xFF2A4BB5.toInt(), setOf("iso", "chd", "cso", "zso", "gz", "bin"), 2000..2013),
    SWITCH("Nintendo Switch", 0xFFE60012.toInt(), setOf("nsp", "xci", "nsz", "xcz"), 2017..2100),
    PS1("PlayStation", 0xFF6B6F80.toInt(), setOf("cue", "m3u", "pbp", "chd", "iso"), 1994..2006),
    PSP("PSP", 0xFF4A4A55.toInt(), setOf("iso", "cso", "pbp", "chd"), 2004..2016),
    GC("GameCube / Wii", 0xFF6A5ACD.toInt(), setOf("iso", "rvz", "wbfs", "gcm", "wia", "ciso"), 2001..2014),
    N3DS("Nintendo 3DS", 0xFFCE181E.toInt(), setOf("3ds", "cia", "cci", "cxi"), 2011..2020),
    NDS("Nintendo DS", 0xFF8C8C99.toInt(), setOf("nds", "zip", "7z"), 2004..2014),
    GBA("Game Boy Advance", 0xFF4B3BA8.toInt(), setOf("gba", "zip", "7z"), 2001..2008),
    GB("Game Boy", 0xFF6E8B0F.toInt(), setOf("gb", "gbc", "zip", "7z"), 1989..2003),
    SNES("Super Nintendo", 0xFF7B5EA7.toInt(), setOf("sfc", "smc", "zip", "7z"), 1990..1999),
    NES("NES", 0xFFB22222.toInt(), setOf("nes", "zip", "7z"), 1983..1995),
    N64("Nintendo 64", 0xFF009E4F.toInt(), setOf("n64", "z64", "v64", "zip", "7z"), 1996..2003),
    MD("Mega Drive", 0xFF2B2B33.toInt(), setOf("md", "gen", "smd", "bin", "zip", "7z"), 1988..1998),
    // Gerações mais novas (emuladores de Android recentes: aPS3e/RPCS3, shadPS4, Vita3K, aX360e, Cemu, Flycast/Redream).
    PS3("PlayStation 3", 0xFF1C1C24.toInt(), setOf("iso", "pkg", "chd"), 2006..2017),
    PS4("PlayStation 4", 0xFF00439C.toInt(), setOf("pkg"), 2013..2027),
    VITA("PS Vita", 0xFF1F3B73.toInt(), setOf("vpk", "zip"), 2011..2019),
    X360("Xbox 360", 0xFF107C10.toInt(), setOf("iso", "xex", "god", "zar"), 2005..2016),
    WIIU("Wii U", 0xFF009AC7.toInt(), setOf("wua", "wud", "wux", "rpx"), 2012..2018),
    DC("Dreamcast", 0xFFE8701A.toInt(), setOf("cdi", "gdi", "chd"), 1998..2002),
}

class Rom(val nome: String, val uri: Uri, val sistema: Sistema) {
    /**
     * Nome do arquivo com a região ("Sonic (USA).md"), tirado do endereço do documento: é por ele que o Daijishō
     * casa as capas. "" se o provedor de arquivos não mostra o nome no endereço.
     */
    val arquivo: String
        get() = (uri.lastPathSegment ?: "").substringAfterLast('/').substringAfterLast(':')
            .takeIf { it.substringAfterLast('.', "").lowercase() in sistema.extensoes }.orEmpty()
}

/** Lê as pastas de ROMs escolhidas pela pessoa (sem permissão ampla de arquivos) e abre cada jogo no emulador certo. */
object Roms {

    /** Como cada emulador recebe o jogo. */
    private enum class Modo { BOOTPATH, VIEW, DOLPHIN, GENERICO }

    /**
     * Emulador conhecido: pacotes possíveis, plataformas e a tela que abre o jogo direto.
     * [reserva]: só serve de último recurso (o GameHub emula Windows; Eden/Yuzu e afins vêm antes dele no Switch).
     */
    private class Conhecido(
        val pacotes: List<String>, val sistemas: Set<Sistema>, val modo: Modo, val atividade: String? = null,
        val reserva: Boolean = false,
    )

    private val RETRO = setOf(Sistema.NES, Sistema.SNES, Sistema.GB, Sistema.GBA, Sistema.MD, Sistema.N64, Sistema.NDS, Sistema.PS1)

    // Ordem = preferência no "Automático".
    private val CONHECIDOS = listOf(
        Conhecido(listOf("xyz.aethersx2.android"), setOf(Sistema.PS2), Modo.BOOTPATH, "xyz.aethersx2.android.EmulationActivity"),
        Conhecido(listOf("dev.eden.eden_emulator", "com.miHoYo.Yuanshen.nightly", "org.yuzu.yuzu_emu"), setOf(Sistema.SWITCH),
            Modo.VIEW, "org.yuzu.yuzu_emu.activities.EmulationActivity"),
        Conhecido(listOf("com.github.stenzek.duckstation"), setOf(Sistema.PS1), Modo.BOOTPATH,
            "com.github.stenzek.duckstation.EmulationActivity"),
        Conhecido(listOf("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp"), setOf(Sistema.PSP), Modo.VIEW, "org.ppsspp.ppsspp.PpssppActivity"),
        Conhecido(listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.mmjr"), setOf(Sistema.GC), Modo.DOLPHIN,
            "org.dolphinemu.dolphinemu.ui.main.MainActivity"),
        Conhecido(listOf("io.github.lime3ds.android", "org.citra.citra_emu", "org.citra.emu"), setOf(Sistema.N3DS), Modo.VIEW),
        Conhecido(listOf("me.magnum.melonds"), setOf(Sistema.NDS), Modo.VIEW),
        Conhecido(listOf("com.dsemu.drastic"), setOf(Sistema.NDS), Modo.VIEW),
        Conhecido(listOf("com.xiaoji.egggame"), setOf(Sistema.SWITCH), Modo.GENERICO, reserva = true),
        Conhecido(listOf("org.vita3k.emulator"), setOf(Sistema.VITA), Modo.GENERICO),
        Conhecido(listOf("info.cemu.cemu", "info.cemu.Cemu"), setOf(Sistema.WIIU), Modo.GENERICO),
        Conhecido(listOf("com.flycast.emulator", "io.recompiled.redream"), setOf(Sistema.DC), Modo.GENERICO),
        Conhecido(listOf("com.retroarch.aarch64", "com.retroarch"), RETRO, Modo.GENERICO),
    )

    /**
     * Emuladores sem pacote fixo conhecido (versões novas e forks mudam de nome): qualquer app instalado cujo
     * pacote tenha uma destas palavras entra como emulador da plataforma (abre o jogo pelo jeito genérico).
     */
    private val PALAVRAS = mapOf(
        Sistema.PS3 to listOf("rpcs3", "aps3e", "ps3"),
        Sistema.PS4 to listOf("shadps4", "ps4", "kyty"),
        Sistema.VITA to listOf("vita3k", "vita"),
        Sistema.X360 to listOf("ax360e", "x360", "xenia", "xbox360"),
        Sistema.WIIU to listOf("cemu"),
        Sistema.DC to listOf("flycast", "redream", "reicast"),
        Sistema.SWITCH to listOf("eden", "yuzu", "sudachi", "citron", "suyu", "torzu", "kenji", "ryujinx", "skyline", "strato"),
        Sistema.PS2 to listOf("aethersx2", "nethersx2", "pcsx2"),
        Sistema.N3DS to listOf("azahar", "citra", "lime3ds"),
    )

    /** Pacotes de apps instalados que aparecem na gaveta (o launcher já enxerga esses pela consulta do manifesto). */
    private fun apps(ctx: Context): List<String> = ctx.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0),
    ).map { it.activityInfo.packageName }.distinct()

    private fun porPalavra(ctx: Context, sistema: Sistema): List<String> {
        val palavras = PALAVRAS[sistema] ?: return emptyList()
        // A palavra precisa começar um pedaço do pacote ("dev.eden.…", "aenu.aps3e"), para "eden" não pegar "sweden".
        return apps(ctx).filter { p -> p != ctx.packageName && palavras.any { w -> Regex("(^|[._])$w").containsMatchIn(p.lowercase()) } }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)

    /** Emuladores instalados que servem para a plataforma, na ordem de preferência. */
    fun candidatos(ctx: Context, sistema: Sistema): List<String> {
        val (reservas, principais) = CONHECIDOS.filter { sistema in it.sistemas }.partition { it.reserva }
        fun instalados(l: List<Conhecido>) = l.flatMap { it.pacotes }.filter { instalado(ctx, it) }
        return (instalados(principais) + porPalavra(ctx, sistema) + instalados(reservas) +
            // App escolhido à mão em "Outro app instalado…" (qualquer um, mesmo desconhecido).
            listOfNotNull(escolhido(ctx, sistema)?.takeIf { instalado(ctx, it) })).distinct()
    }

    /** Emuladores instalados de todas as plataformas (conhecidos, achados pelo nome do pacote e escolhidos à mão). */
    fun emuladoresInstalados(ctx: Context): List<String> = Sistema.entries.flatMap { candidatos(ctx, it) }
        .filter { it != ctx.packageName }.distinct()

    /** Todos os apps instalados (para "Outro app instalado…"), os que parecem emuladores primeiro. */
    fun todosOsApps(ctx: Context): List<String> {
        val emus = PALAVRAS.values.flatten() + listOf("emu", "retroarch", "dolphin", "ppsspp", "duckstation", "melonds", "drastic")
        return apps(ctx).filter { it != ctx.packageName }
            .sortedWith(compareBy({ p -> if (emus.any { p.lowercase().contains(it) }) 0 else 1 }, { it }))
    }

    fun escolhido(ctx: Context, sistema: Sistema): String? = prefs(ctx).getString("emu_${sistema.name}", null)

    /**
     * Uma vez só: versões antigas deixavam o GameHub como emulador de Switch. Se houver um emulador de Switch de
     * verdade instalado, essa escolha volta para "Automático" (a pessoa ainda pode escolher o GameHub de novo).
     */
    fun corrigirReservas(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("reserva_corrigida", false)) return
        val e = p.edit().putBoolean("reserva_corrigida", true)
        for (sistema in Sistema.entries) {
            val atual = escolhido(ctx, sistema) ?: continue
            val reservas = CONHECIDOS.filter { sistema in it.sistemas && it.reserva }.flatMap { it.pacotes }
            if (atual in reservas && candidatos(ctx, sistema).any { it !in reservas }) e.remove("emu_${sistema.name}")
        }
        e.apply()
    }

    fun escolher(ctx: Context, sistema: Sistema, pacote: String?) {
        prefs(ctx).edit().apply { if (pacote == null) remove("emu_${sistema.name}") else putString("emu_${sistema.name}", pacote) }.apply()
    }

    // ---------- Pasta → plataforma ----------

    fun sistemaDaPasta(ctx: Context, pasta: Uri): Sistema? =
        prefs(ctx).getString("pasta_sistema:$pasta", null)?.let { n -> Sistema.entries.firstOrNull { it.name == n } }

    fun definirSistemaDaPasta(ctx: Context, pasta: Uri, sistema: Sistema?) {
        prefs(ctx).edit().apply {
            if (sistema == null) remove("pasta_sistema:$pasta") else putString("pasta_sistema:$pasta", sistema.name)
        }.apply()
    }

    /** Extensões que, sozinhas, já dizem o sistema. As ambíguas (iso, bin, zip…) dependem do nome da pasta. */
    private val PELA_EXTENSAO = mapOf(
        "nsp" to Sistema.SWITCH, "xci" to Sistema.SWITCH, "nsz" to Sistema.SWITCH, "xcz" to Sistema.SWITCH,
        "iso" to Sistema.PS2, "chd" to Sistema.PS2, "cso" to Sistema.PS2, "zso" to Sistema.PS2, "gz" to Sistema.PS2,
        "cue" to Sistema.PS1, "m3u" to Sistema.PS1, "pbp" to Sistema.PSP,
        "rvz" to Sistema.GC, "wbfs" to Sistema.GC, "gcm" to Sistema.GC, "wia" to Sistema.GC, "ciso" to Sistema.GC,
        "3ds" to Sistema.N3DS, "cia" to Sistema.N3DS, "cci" to Sistema.N3DS, "cxi" to Sistema.N3DS,
        "nds" to Sistema.NDS, "gba" to Sistema.GBA, "gb" to Sistema.GB, "gbc" to Sistema.GB,
        "sfc" to Sistema.SNES, "smc" to Sistema.SNES, "nes" to Sistema.NES,
        "n64" to Sistema.N64, "z64" to Sistema.N64, "v64" to Sistema.N64,
        "md" to Sistema.MD, "gen" to Sistema.MD, "smd" to Sistema.MD,
        "vpk" to Sistema.VITA, "xex" to Sistema.X360, "god" to Sistema.X360,
        "wua" to Sistema.WIIU, "wud" to Sistema.WIIU, "wux" to Sistema.WIIU, "rpx" to Sistema.WIIU,
        "cdi" to Sistema.DC, "gdi" to Sistema.DC,
    )

    // Pastas das gerações novas primeiro: "ps3" não pode cair em "ps" de outra regra, e .iso/.pkg dependem da pasta.
    private val PELA_PASTA: List<Pair<Sistema, List<String>>> = listOf(
        Sistema.PS3 to listOf("ps3", "playstation 3", "rpcs3", "aps3e"),
        Sistema.PS4 to listOf("ps4", "playstation 4", "shadps4"),
        Sistema.VITA to listOf("vita", "psvita", "ps vita", "vita3k"),
        Sistema.X360 to listOf("xbox 360", "xbox360", "x360", "xenia"),
        Sistema.WIIU to listOf("wii u", "wiiu", "cemu"),
        Sistema.DC to listOf("dreamcast", "dc", "flycast", "redream"),
        Sistema.PS2 to listOf("ps2", "playstation 2", "pcsx2", "aethersx2", "nethersx2"),
        Sistema.SWITCH to listOf("switch", "nsw", "yuzu", "eden"),
        Sistema.PSP to listOf("psp", "ppsspp"),
        Sistema.PS1 to listOf("ps1", "psx", "duckstation", "playstation"),
        Sistema.GC to listOf("gc", "gamecube", "wii", "dolphin"),
        Sistema.N3DS to listOf("3ds", "citra", "lime3ds"),
        Sistema.NDS to listOf("nds", "ds", "melonds", "drastic"),
        Sistema.GBA to listOf("gba"),
        Sistema.GB to listOf("gb", "gbc"),
        Sistema.SNES to listOf("snes", "sfc", "super nintendo"),
        Sistema.NES to listOf("nes", "famicom"),
        Sistema.N64 to listOf("n64", "nintendo 64"),
        Sistema.MD to listOf("md", "megadrive", "mega drive", "genesis"),
    )

    class Varredura(val roms: List<Rom>, val pastasFora: Int)

    /**
     * Lê cada pasta; numa pasta com plataforma definida, todo arquivo daquela plataforma entra nela.
     * Pasta que não abre (cartão SD fora, por exemplo) mantém os jogos da leitura [anteriores].
     */
    fun varrer(ctx: Context, pastas: List<Uri>, anteriores: List<Rom>): Varredura {
        val achadas = ArrayList<Rom>()
        var fora = 0
        for (pasta in pastas) {
            val daPasta = ArrayList<Rom>()
            val ok = try {
                val raiz = DocumentsContract.getTreeDocumentId(pasta)
                acessivel(ctx, pasta, raiz) && run {
                    percorrer(ctx, pasta, raiz, "", 0, daPasta, sistemaDaPasta(ctx, pasta))
                    true
                }
            } catch (_: Exception) {
                false
            }
            if (ok) {
                achadas += daPasta
            } else {
                fora++
                // Os documentos de uma pasta têm o endereço dela como começo.
                achadas += anteriores.filter { it.uri.toString().startsWith(pasta.toString()) }
            }
        }
        val lista = achadas.distinctBy { it.uri }.sortedWith(compareBy({ it.sistema.ordinal }, { it.nome.lowercase() }))
        return Varredura(lista, fora)
    }

    private fun acessivel(ctx: Context, arvore: Uri, raiz: String): Boolean = try {
        ctx.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(arvore, raiz),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (_: Exception) {
        false
    }

    private fun percorrer(
        ctx: Context, arvore: Uri, docId: String, caminho: String, nivel: Int, saida: MutableList<Rom>, fixo: Sistema?,
    ) {
        if (nivel > 4) return
        val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(arvore, docId)
        val colunas = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        ctx.contentResolver.query(filhos, colunas, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val nome = c.getString(1) ?: continue
                if (nome.startsWith(".")) continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    percorrer(ctx, arvore, id, "$caminho/$nome", nivel + 1, saida, fixo)
                } else {
                    val ext = nome.substringAfterLast('.', "").lowercase()
                    val sistema = (if (fixo != null) fixo.takeIf { ext in it.extensoes } else sistemaDe(nome, caminho)) ?: continue
                    // Xbox 360 extraído: o jogo é o "default.xex" da pasta dele (o nome vem da pasta). Os outros .xex
                    // são arquivos do sistema do console (AvatarEditor, dash, Guide…), não jogos.
                    if (ext == "xex") {
                        if (!nome.equals("default.xex", ignoreCase = true)) continue
                        val pastaDoJogo = caminho.substringAfterLast('/')
                        if (pastaDoJogo.isEmpty() || pastaDoJogo.startsWith("$") || pastaDoJogo.matches(Regex("[0-9A-Fa-f]{8,16}"))) continue
                        saida += Rom(limparNome("$pastaDoJogo.xex"), DocumentsContract.buildDocumentUriUsingTree(arvore, id), sistema)
                        continue
                    }
                    saida += Rom(limparNome(nome), DocumentsContract.buildDocumentUriUsingTree(arvore, id), sistema)
                }
            }
        }
    }

    private fun sistemaDe(arquivo: String, caminho: String): Sistema? {
        val ext = arquivo.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        val dica = caminho.lowercase()
        val pelaPasta = PELA_PASTA.firstOrNull { (_, nomes) ->
            nomes.any { Regex("(^|[^a-z0-9])${Regex.escape(it)}([^a-z0-9]|$)").containsMatchIn(dica) }
        }?.first
        if (pelaPasta != null && ext in pelaPasta.extensoes) return pelaPasta
        // Sem pista na pasta, .bin/.zip/.7z são ambíguos demais: ignora.
        return PELA_EXTENSAO[ext]
    }

    private fun limparNome(arquivo: String) = arquivo.substringBeforeLast('.')
        .replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
        .replace('_', ' ')
        .trim()
        .ifEmpty { arquivo }

    /** Emulador usado na plataforma: o escolhido nas Configurações (se instalado) ou o primeiro conhecido. */
    fun emulador(ctx: Context, sistema: Sistema): String? {
        val lista = candidatos(ctx, sistema)
        return escolhido(ctx, sistema)?.takeIf { it in lista } ?: lista.firstOrNull()
    }

    /** Intent que abre o jogo e, se o emulador não aceitar abrir direto, um aviso para mostrar. */
    class Abertura(val intent: Intent, val aviso: String?)

    /** Todos os emuladores conhecidos instalados (para escolher um por jogo, mesmo de outra plataforma). */
    fun todosInstalados(ctx: Context): List<String> =
        (CONHECIDOS.flatMap { it.pacotes }.filter { instalado(ctx, it) } + Sistema.entries.flatMap { porPalavra(ctx, it) }).distinct()

    // ---------- Escolhas por jogo (valem mais que as da plataforma) ----------

    fun emuladorDoJogo(ctx: Context, rom: Rom): String? = prefs(ctx).getString("emu_rom:${rom.uri}", null)

    fun definirEmuladorDoJogo(ctx: Context, rom: Rom, pacote: String?) {
        prefs(ctx).edit().apply { if (pacote == null) remove("emu_rom:${rom.uri}") else putString("emu_rom:${rom.uri}", pacote) }.apply()
    }

    /** Emulador que vai abrir este jogo: o escolhido para o jogo, senão o da plataforma. */
    fun emuladorEfetivo(ctx: Context, rom: Rom): String? =
        emuladorDoJogo(ctx, rom)?.takeIf { instalado(ctx, it) } ?: emulador(ctx, rom.sistema)

    fun abertura(ctx: Context, rom: Rom): Abertura? {
        val pacote = emuladorEfetivo(ctx, rom)
        val pm = ctx.packageManager
        fun preparar(i: Intent) = i.apply {
            // O emulador só lê o arquivo porque o GameLauncherTV repassa a permissão da pasta.
            clipData = ClipData.newRawUri(rom.nome, rom.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (pacote == null) {
            val i = preparar(Intent(Intent.ACTION_VIEW).setData(rom.uri))
            return if (i.resolveActivity(pm) != null) Abertura(i, null) else null
        }
        // Emulador achado pelo nome ou escolhido à mão: abre do jeito genérico.
        val conhecido = CONHECIDOS.firstOrNull { pacote in it.pacotes } ?: Conhecido(listOf(pacote), setOf(rom.sistema), Modo.GENERICO)
        val i = when (conhecido.modo) {
            Modo.BOOTPATH -> Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pacote, conhecido.atividade!!))
                .putExtra("bootPath", rom.uri.toString())
            Modo.DOLPHIN -> Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pacote, conhecido.atividade!!))
                .putExtra("AutoStartFiles", arrayOf(rom.uri.toString()))
            // Só o endereço: o Android descobre o tipo do arquivo pela pasta (como no teste que abriu o Mario Kart).
            Modo.VIEW -> Intent(Intent.ACTION_VIEW).setData(rom.uri).apply {
                if (conhecido.atividade != null) setComponent(ComponentName(pacote, conhecido.atividade)) else setPackage(pacote)
            }
            Modo.GENERICO -> Intent(Intent.ACTION_VIEW).setData(rom.uri).setPackage(pacote)
        }
        preparar(i)
        if (i.resolveActivity(pm) != null) return Abertura(i, null)
        // O emulador não abre jogos de fora: abre o emulador e avisa.
        val principal = pm.getLaunchIntentForPackage(pacote) ?: return null
        return Abertura(preparar(principal), "Este emulador não abre jogos direto. Escolha \"${rom.nome}\" dentro dele.")
    }

    private fun instalado(ctx: Context, pacote: String) = try {
        ctx.packageManager.getPackageInfo(pacote, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // ---------- Cache da lista (abre na hora; a varredura nova roda em segundo plano) ----------

    private fun arquivo(ctx: Context) = File(ctx.filesDir, "roms_v1.json")

    fun lerCache(ctx: Context): List<Rom> = try {
        val a = JSONArray(arquivo(ctx).readText())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Rom(o.getString("n"), Uri.parse(o.getString("u")), Sistema.valueOf(o.getString("s")))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun salvarCache(ctx: Context, roms: List<Rom>) {
        val a = JSONArray()
        roms.forEach { a.put(JSONObject().put("n", it.nome).put("u", it.uri.toString()).put("s", it.sistema.name)) }
        arquivo(ctx).writeText(a.toString())
    }
}
