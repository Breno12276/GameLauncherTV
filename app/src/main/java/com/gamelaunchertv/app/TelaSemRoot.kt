package com.gamelaunchertv.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Resolução e densidade da tela SEM root, como o Second Screen: com a permissão WRITE_SECURE_SETTINGS (dada uma
 * vez pelo PC: "adb shell pm grant <pacote> android.permission.WRITE_SECURE_SETTINGS"), o próprio serviço de
 * janelas do Android aceita o tamanho e a densidade forçados — o mesmo que o "wm size" / "wm density" fazem.
 * Lê o estado atual pelas APIs públicas (tamanho físico da tela e densidade de fábrica).
 */
object TelaSemRoot {

    fun permitido(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    // ---------- Sem PC: pelo Shizuku ----------

    /** Pacotes do app Shizuku (o oficial e o fork mantido "Shizuku+"/thedjchi). */
    private val PACOTES_SHIZUKU = listOf("moe.shizuku.privileged.api", "com.thedjchi.shizuku")

    fun shizukuInstalado(ctx: Context) = PACOTES_SHIZUKU.any {
        try { ctx.packageManager.getPackageInfo(it, PackageManager.PackageInfoFlags.of(0)); true } catch (_: Exception) { false }
    }

    /** O serviço do Shizuku está rodando (ligado pela Depuração sem fio ou por root). */
    fun shizukuRodando() = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun shizukuPermitido() = try {
        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * Pede ao Shizuku a permissão de usá-lo (a janela é do próprio Shizuku); [pronto] recebe se a pessoa aceitou.
     * Se já tem, responde na hora.
     */
    fun pedirShizuku(pronto: (Boolean) -> Unit) {
        if (shizukuPermitido()) return pronto(true)
        val codigo = 4242
        val ouvinte = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(c: Int, resultado: Int) {
                if (c != codigo) return
                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                pronto(resultado == PackageManager.PERMISSION_GRANTED)
            }
        }
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(ouvinte)
        try {
            rikka.shizuku.Shizuku.requestPermission(codigo)
        } catch (_: Throwable) {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(ouvinte)
            pronto(false)
        }
    }

    /**
     * Com o Shizuku permitido: roda "pm grant" com o poder de shell do Shizuku e libera WRITE_SECURE_SETTINGS
     * para o launcher (fica valendo depois de reiniciar; o Shizuku não é mais necessário). Chamar fora da tela.
     */
    fun liberarPeloShizuku(ctx: Context): Boolean = try {
        // newProcess é privado na API 13 do Shizuku, mas continua lá (é o jeito de rodar um comando de shell).
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"), null, null) as Process
        p.waitFor()
        permitido(ctx)
    } catch (_: Throwable) {
        false
    }

    /** Comando para dar a permissão pelo PC (adb). */
    fun comando(ctx: Context) = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** Uma vez: libera as funções internas do serviço de janelas (o Android bloqueia para apps com targetSdk novo). */
    private val liberado by lazy {
        try { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/view/IWindowManager", "Landroid/view/WindowManagerGlobal") }
        catch (_: Throwable) { false }
    }

    private fun servico(): Any {
        liberado
        return Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
    }

    private fun metodo(nome: String, vararg tipos: Class<*>) = servico().javaClass.getMethod(nome, *tipos)

    /** Este Android deixa chamar o serviço de janelas (alguns bloqueiam as funções internas para apps). */
    fun suportado(): Boolean = try {
        metodo("setForcedDisplaySize", Int::class.java, Int::class.java, Int::class.java)
        metodo("setForcedDisplayDensityForUser", Int::class.java, Int::class.java, Int::class.java)
        true
    } catch (_: Throwable) {
        false
    }

    /** Tamanho e densidade como o [RecursosRoot.Tela] (físico e atual), para a mesma tela de escolha e o "Desfazer". */
    fun tela(ctx: Context): RecursosRoot.Tela {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val modo = ctx.display?.mode ?: wm.defaultDisplay.mode
        val atual = wm.maximumWindowMetrics.bounds
        // A tela física em retrato (como o "wm size" mostra); a atual na mesma orientação.
        val fl = minOf(modo.physicalWidth, modo.physicalHeight)
        val fa = maxOf(modo.physicalWidth, modo.physicalHeight)
        return RecursosRoot.Tela(fl, fa, minOf(atual.width(), atual.height()), maxOf(atual.width(), atual.height()),
            DisplayMetrics.DENSITY_DEVICE_STABLE, ctx.resources.configuration.densityDpi)
    }

    /** Resolução [r] (null = não mexe; 0 × 0 = a original) e [densidade] (0 = original, null = não mexe). */
    fun definir(ctx: Context, r: RecursosRoot.Resolucao?, densidade: Int?): Boolean = try {
        when {
            r == null -> Unit
            r.curto == 0 -> metodo("clearForcedDisplaySize", Int::class.java).invoke(servico(), 0)
            // Em retrato (lado curto primeiro), como a tela física do celular e o "wm size".
            else -> metodo("setForcedDisplaySize", Int::class.java, Int::class.java, Int::class.java).invoke(servico(), 0, r.curto, r.longo)
        }
        when (densidade) {
            null -> Unit
            // O usuário deste app (o -2, "usuário atual", pede outra permissão que apps não têm).
            0 -> metodo("clearForcedDisplayDensityForUser", Int::class.java, Int::class.java).invoke(servico(), 0, android.os.Process.myUserHandle().hashCode())
            else -> metodo("setForcedDisplayDensityForUser", Int::class.java, Int::class.java, Int::class.java).invoke(servico(), 0, densidade, android.os.Process.myUserHandle().hashCode())
        }
        true
    } catch (_: Throwable) {
        false
    }

    /** Volta exatamente para um estado lido antes. */
    fun voltar(ctx: Context, t: RecursosRoot.Tela) {
        try {
            if (t.atualL == t.fisicaL && t.atualA == t.fisicaA) metodo("clearForcedDisplaySize", Int::class.java).invoke(servico(), 0)
            else metodo("setForcedDisplaySize", Int::class.java, Int::class.java, Int::class.java).invoke(servico(), 0, t.atualL, t.atualA)
        } catch (_: Throwable) {}
        definir(ctx, null, if (t.densAtual == t.densFisica) 0 else t.densAtual)
    }
}
