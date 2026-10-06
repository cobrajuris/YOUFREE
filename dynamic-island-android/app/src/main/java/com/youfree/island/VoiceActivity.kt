package com.youfree.island

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Tela transparente que fica por cima do app atual só enquanto o assistente
 * escuta a voz (ou enquanto você digita). Tocar fora cancela.
 */
class VoiceActivity : Activity() {

    private lateinit var rootView: FrameLayout
    private var recognizer: SpeechRecognizer? = null
    private var delivered = false

    private val island: IslandController?
        get() = IslandHub.controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootView = FrameLayout(this).apply {
            setOnClickListener { cancel() }
        }
        setContentView(rootView)

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

    /** Barra flutuante de digitar, no estilo "Digitar para a Siri". */
    private fun showTypingDialog() {
        val root = rootView
        root.setBackgroundColor(0x59000000)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(220).start()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
            background = Ui.rounded(Ui.SURFACE, Ui.dpf(context, 28f), Ui.HAIRLINE, 1)
            elevation = Ui.dpf(context, 12f)
            isClickable = true
        }
        bar.addView(OrbView(this), LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)))
        val input = EditText(this).apply {
            hint = "Pergunte à ${Prefs(this@VoiceActivity).assistantName}…"
            setHintTextColor(Ui.TERTIARY)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Ui.font(this@VoiceActivity, Ui.Weight.REGULAR)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            background = null
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 8), 0)
        }
        bar.addView(input, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        val send = Ui.circle(this, R.drawable.ic_arrow_up, 38, bg = Ui.FILL, iconDp = 20) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) deliver(text)
        }
        send.alpha = 0.5f
        bar.addView(send, LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val ready = !s.isNullOrBlank()
                send.background = Ui.oval(if (ready) Ui.BLUE else Ui.FILL)
                send.alpha = if (ready) 1f else 0.5f
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) deliver(text)
            true
        }

        val margin = Ui.dp(this, 12)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(margin, 0, margin, margin)
        })
        bar.translationY = Ui.dpf(this, 80f)
        bar.animate().translationY(0f).setDuration(520).setInterpolator(Ui.SPRING_SMOOTH).start()

        input.requestFocus()
        input.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
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
