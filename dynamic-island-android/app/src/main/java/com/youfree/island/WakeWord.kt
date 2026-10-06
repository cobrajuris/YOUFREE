package com.youfree.island

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Palavra de ativação "Oi assistente", 100% no celular (Vosk, código aberto: github.com/alphacep/vosk-api).
 * O áudio nunca sai do aparelho: o reconhecedor só conhece as frases de ativação.
 */
class WakeWord(private val ctx: Context, private val onWake: () -> Unit) {

    private var model: Model? = null
    private var service: SpeechService? = null
    private var lastWake = 0L
    @Volatile
    private var released = false

    val running: Boolean get() = service != null

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onError(exception: Exception?) {
            stop()
        }

        override fun onTimeout() = Unit
    }

    private fun check(json: String?, key: String) {
        val text = try {
            JSONObject(json ?: return).optString(key)
        } catch (_: Exception) {
            return
        }
        val n = Assistant.normalize(text)
        if (n.contains("assistente") && Regex("\\b(oi|ei|ola|hey)\\b").containsMatchIn(n)) {
            val now = System.currentTimeMillis()
            if (now - lastWake < 3_000) return
            lastWake = now
            IslandHub.main.post { onWake() }
        }
    }

    /** Começa a escutar (carrega o modelo numa thread separada na primeira vez). */
    fun start() {
        if (released || service != null || !isModelReady(ctx)) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            try {
                val m = model ?: Model(modelDir(ctx).absolutePath).also { model = it }
                val grammar = "[\"oi assistente\", \"ei assistente\", \"olá assistente\", \"ola assistente\", \"[unk]\"]"
                val rec = Recognizer(m, 16000f, grammar)
                IslandHub.main.post {
                    if (released || service != null) {
                        rec.close()
                        return@post
                    }
                    try {
                        service = SpeechService(rec, 16000f).also { it.startListening(listener) }
                    } catch (_: Exception) {
                        rec.close()
                    }
                }
            } catch (_: Throwable) {
                // Modelo corrompido ou sem memória: fica desligado.
            }
        }.start()
    }

    /** Solta o microfone (para a assistente ouvir o comando, ligações etc.). */
    fun stop() {
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
    }

    fun release() {
        released = true
        stop()
        model?.close()
        model = null
    }

    companion object {
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"

        fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk-pt")

        fun isModelReady(ctx: Context): Boolean = File(modelDir(ctx), "am").isDirectory

        /** Baixa o modelo em português (~31 MB) e descompacta. Roda numa thread própria. */
        fun download(ctx: Context, progress: (Int) -> Unit, done: (Boolean, String?) -> Unit) {
            Thread {
                val zip = File(ctx.cacheDir, "vosk-pt.zip")
                try {
                    val conn = URI.create(MODEL_URL).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    val total = conn.contentLengthLong.coerceAtLeast(1L)
                    conn.inputStream.use { input ->
                        FileOutputStream(zip).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            var last = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != last) {
                                    last = pct
                                    IslandHub.main.post { progress(pct) }
                                }
                            }
                        }
                    }
                    val target = modelDir(ctx)
                    target.deleteRecursively()
                    target.mkdirs()
                    ZipInputStream(zip.inputStream().buffered()).use { zin ->
                        while (true) {
                            val entry = zin.nextEntry ?: break
                            // Tira a pasta de cima (vosk-model-small-pt-0.3/...).
                            val rel = entry.name.substringAfter('/', "")
                            if (rel.isEmpty() || rel.contains("..")) continue
                            val f = File(target, rel)
                            if (entry.isDirectory) {
                                f.mkdirs()
                            } else {
                                f.parentFile?.mkdirs()
                                FileOutputStream(f).use { zin.copyTo(it) }
                            }
                        }
                    }
                    zip.delete()
                    val ok = isModelReady(ctx)
                    IslandHub.main.post { done(ok, if (ok) null else "Arquivo do modelo incompleto.") }
                } catch (e: Exception) {
                    zip.delete()
                    IslandHub.main.post { done(false, e.message ?: e.javaClass.simpleName) }
                }
            }.start()
        }
    }
}
