package com.gamelaunchertv.app

import android.content.Context

/**
 * Backup dos saves dos emuladores (com root): os dados internos do app (/data/data/<pacote>) e a pasta dele em
 * Android/data, num .tar.gz em /sdcard/GameLauncherTV/Saves/<pacote>/. Guarda dono e rótulos (--numeric-owner,
 * --selinux) para a restauração voltar exatamente como estava. Ficam de fora caches e shaders (refeitos sozinhos)
 * e conteúdo instalado grande (atualizações e DLCs do Switch). Mantém os 3 últimos de cada emulador.
 */
object Saves {

    const val PASTA = "/sdcard/GameLauncherTV/Saves"
    private const val MANTER = 3

    private val FORA = listOf("*/cache", "*/code_cache", "*/shadercache", "*/shader_cache", "*/shaders", "*/ShaderCache",
        "*/GPUCache", "*/no_backup", "*/lib", "*/nand/user/Contents", "*/load", "*/dump", "*.iso", "*.chd", "*.nsp", "*.xci")

    /** Um backup: [pacote], caminho do arquivo, data (ms) e tamanho (bytes). */
    class Copia(val pacote: String, val arquivo: String, val quando: Long, val tamanho: Long)

    /** Faz o backup de cada emulador de [pacotes]; devolve (pacote, deu certo). */
    fun fazer(pacotes: List<String>, aoProgredir: (String) -> Unit = {}): List<Pair<String, Boolean>> = pacotes.map { p ->
        aoProgredir(p)
        val q = Root.aspas(p)
        // Só as pastas que existem (nem todo emulador usa Android/data).
        val pastas = Root.rodar("cd /; for d in data/data/$q data/media/0/Android/data/$q; do [ -d \"\$d\" ] && echo \"\$d\"; done")
            .saida.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (pastas.isEmpty()) return@map p to false
        val data = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val destino = "$PASTA/$p/$data.tar.gz"
        val fora = FORA.joinToString(" ") { "--exclude=${Root.aspas(it)}" }
        val ok = Root.rodarSeparado(
            "mkdir -p ${Root.aspas("$PASTA/$p")} && tar -czf ${Root.aspas(destino)} --numeric-owner --selinux $fora -C / " +
                pastas.joinToString(" ") { Root.aspas(it) },
            15 * 60_000L,
        ).ok
        if (!ok) Root.rodar("rm -f ${Root.aspas(destino)}")
        // Só os mais novos ficam (os backups antigos deste mesmo emulador, feitos pelo launcher).
        Root.rodar("ls -1t ${Root.aspas("$PASTA/$p")}/*.tar.gz 2>/dev/null | tail -n +${MANTER + 1} | while read f; do rm -f \"\$f\"; done")
        p to ok
    }

    /** Backups guardados, do mais novo ao mais antigo. */
    fun listar(): List<Copia> {
        val r = Root.rodar("for f in $PASTA/*/*.tar.gz; do [ -f \"\$f\" ] && stat -c '%n|%s|%Y' \"\$f\"; done")
        return r.saida.lines().mapNotNull { l ->
            val partes = l.trim().split('|')
            if (partes.size < 3) return@mapNotNull null
            val pacote = partes[0].removePrefix("$PASTA/").substringBefore('/')
            Copia(pacote, partes[0], (partes[2].toLongOrNull() ?: 0) * 1000, partes[1].toLongOrNull() ?: 0)
        }.sortedByDescending { it.quando }
    }

    /**
     * Restaura [c] por cima dos dados atuais do emulador (fechado antes). Arquivos que não estavam no backup
     * continuam lá; os do backup voltam com o dono e o rótulo de segurança originais.
     */
    fun restaurar(c: Copia): Boolean {
        val q = Root.aspas(c.pacote)
        return Root.rodarSeparado(
            "am force-stop $q; tar -xzf ${Root.aspas(c.arquivo)} --numeric-owner --selinux -C / && " +
                "{ restorecon -RF /data/data/$q /data/media/0/Android/data/$q 2>/dev/null; true; }",
            15 * 60_000L,
        ).ok
    }

    /** Backup automático (preferência "saves_auto": 0 = desligado, 1 = diário, 2 = semanal) se já passou o prazo. */
    fun automaticoSePreciso(ctx: Context) {
        val p = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)
        val prazo = when (p.getInt("saves_auto", 0)) { 1 -> 24L; 2 -> 24L * 7; else -> return } * 60 * 60 * 1000
        if (!Root.ligado(ctx) || System.currentTimeMillis() - p.getLong("saves_ultimo", 0) < prazo) return
        p.edit().putLong("saves_ultimo", System.currentTimeMillis()).apply()
        fazer(Roms.emuladoresInstalados(ctx))
    }

    fun tamanhoLegivel(b: Long) = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
        else -> "${(b / 1024).coerceAtLeast(1)} KB"
    }
}
