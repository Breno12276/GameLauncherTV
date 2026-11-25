package com.gamelaunchertv.app

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process

/** Atalho fixado na tela inicial por um app (ex.: um jogo do GameHub). */
class Atalho(val pacote: String, val id: String, val nome: String, val icone: Bitmap?)

/**
 * Atalhos fixados ("pinned shortcuts"): o mecanismo oficial do Android para um app pôr um item
 * na tela inicial. Como o GameLauncherTV é a tela inicial padrão, ela recebe os pedidos (ConfirmarAtalho)
 * e o próprio Android guarda quais atalhos estão fixados — aqui só lemos, abrimos e removemos.
 */
object Atalhos {

    private fun la(ctx: Context) = ctx.getSystemService(LauncherApps::class.java)

    /** Só a tela inicial padrão pode ler e abrir atalhos de outros apps. */
    fun disponivel(ctx: Context) = try { la(ctx).hasShortcutHostPermission() } catch (_: Exception) { false }

    fun listar(ctx: Context): List<Atalho> {
        if (!disponivel(ctx)) return emptyList()
        val q = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val lista = try { la(ctx).getShortcuts(q, Process.myUserHandle()).orEmpty() } catch (_: Exception) { emptyList() }
        return lista.filter { it.isEnabled }.map { para(ctx, it) }.sortedBy { it.nome.lowercase() }
    }

    fun para(ctx: Context, s: ShortcutInfo) = Atalho(
        pacote = s.`package`,
        id = s.id,
        nome = (s.shortLabel ?: s.longLabel ?: s.id).toString(),
        icone = icone(ctx, s),
    )

    private fun icone(ctx: Context, s: ShortcutInfo): Bitmap? = try {
        la(ctx).getShortcutIconDrawable(s, ctx.resources.displayMetrics.densityDpi)?.let { d ->
            val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 160, 160)
            d.draw(Canvas(b))
            b
        }
    } catch (_: Exception) {
        null
    }

    fun abrir(ctx: Context, a: Atalho): Boolean = try {
        la(ctx).startShortcut(a.pacote, a.id, null, null, Process.myUserHandle())
        true
    } catch (_: Exception) {
        false
    }

    /** Desafixa: fica fixado só o resto dos atalhos daquele app. */
    fun remover(ctx: Context, a: Atalho) {
        val q = LauncherApps.ShortcutQuery().setPackage(a.pacote).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val restantes = la(ctx).getShortcuts(q, Process.myUserHandle()).orEmpty().map { it.id }.filter { it != a.id }
        la(ctx).pinShortcuts(a.pacote, restantes, Process.myUserHandle())
    }
}
