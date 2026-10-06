package com.youfree.island

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Tela transparente que fica por cima do app atual só enquanto o assistente
 * escuta a voz (ou enquanto você digita). Tocar fora cancela.
 */
class VoiceActivity : Activity() {

    private var recognizer: SpeechRecognizer? = null
    private var delivered = false

    private val island: IslandController?
        get() = IslandHub.controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply {
            setOnClickListener { cancel() }
        })

        if (intent.getBooleanExtra(EXTRA_TYPING, false)) {
            showTypingDialog()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            fail("Preciso do microfone para ouvir você. Permita no app Ilha Assistente.")
        }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Sem serviço de reconhecimento embutido: usa a janela de voz do Google.
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(recognizerIntent(), REQ_SYSTEM_VOICE)
            } catch (e: ActivityNotFoundException) {
                fail("Este celular não tem reconhecimento de voz. Instale o app Google.")
            }
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
            startListening(recognizerIntent())
        }
        island?.onListening()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            island?.onVoiceLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) island?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) fail("Não ouvi nada. Tente de novo.") else deliver(text)
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada. Tente de novo."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Preciso do microfone. Permita no app Ilha Assistente."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem internet para entender a voz."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O microfone está ocupado. Tente de novo."
                else -> "Não consegui ouvir (erro $error)."
            }
            fail(msg)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SYSTEM_VOICE) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !text.isNullOrBlank()) deliver(text) else cancel()
    }

    private fun showTypingDialog() {
        val input = EditText(this).apply {
            hint = "Pergunte qualquer coisa…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(Prefs(this).assistantName)
            .setView(box)
            .setPositiveButton("Enviar") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) deliver(text) else cancel()
            }
            .setNegativeButton("Cancelar") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun deliver(text: String) {
        if (delivered) return
        delivered = true
        val c = island
        if (c == null) {
            Toast.makeText(this, "Ligue a ilha no app Ilha Assistente.", Toast.LENGTH_LONG).show()
        } else {
            c.onUserSaid(text)
        }
        finish()
    }

    private fun fail(message: String) {
        if (delivered) return
        delivered = true
        island?.onVoiceError(message) ?: Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun cancel() {
        if (delivered) return
        delivered = true
        island?.onVoiceCancelled()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Saiu da tela (home, outro app): para de ouvir.
        if (!isChangingConfigurations && recognizer != null) {
            cancel()
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        if (!delivered) island?.onVoiceCancelled()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TYPING = "typing"
        private const val REQ_MIC = 1
        private const val REQ_SYSTEM_VOICE = 2
    }
}
