package com.gamelaunchertv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * O Android abre esta tela quando um app pede para fixar um atalho na tela inicial
 * (ex.: "Criar atalho" de um jogo no GameHub). Pergunta e, se a pessoa aceitar, fixa;
 * o atalho aparece na aba Jogos do GameLauncherTV.
 */
class ConfirmarAtalho : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val pedido = la.getPinItemRequest(intent)
        val info = pedido?.shortcutInfo
        if (pedido == null || !pedido.isValid || pedido.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || info == null) {
            finish()
            return
        }
        val nome = (info.shortLabel ?: info.longLabel ?: info.id).toString()
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.`package`, PackageManager.ApplicationInfoFlags.of(0))).toString()
        } catch (_: Exception) {
            info.`package`
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Adicionar ao GameLauncherTV?")
            .setMessage("\"$nome\" ($app) vai aparecer na aba Jogos, em \"Jogos do $app\".")
            .setPositiveButton("Adicionar") { _, _ ->
                val ok = try { pedido.accept() } catch (_: Exception) { false }
                Toast.makeText(this, if (ok) "\"$nome\" adicionado em Jogos" else "Não foi possível adicionar", Toast.LENGTH_SHORT).show()
                if (ok) {
                    // Volta para o GameLauncherTV já na aba Jogos.
                    startActivity(Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_ABA_JOGOS, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                finish()
            }
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
