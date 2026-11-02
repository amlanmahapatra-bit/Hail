package app.hail.android

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Listening and speaking with Android's built-in engines: free, no extra libraries.
 * Speech recognition runs on the phone when it supports that (Android 13+ with the language
 * pack), else through the phone's standard recognizer. Audio is never sent to Hail's backend;
 * only the recognised text is, like a typed message. Listening happens only after the user taps
 * the mic, or right after Hail has read out options during a voice request.
 */
class Voice(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(activity) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = INDIA
        }
    }

    fun canListen(): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    /** Listens for one utterance. [onResult] gets the text, or null if nothing was understood. */
    fun listen(onPartial: (String) -> Unit, onResult: (String?) -> Unit, preferOnDevice: Boolean = true) {
        stopSpeaking()
        recognizer?.destroy()
        val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
        else SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onError(error: Int) {
                // On-device engine missing the language pack etc.: retry once with the standard one.
                if (onDevice && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    main.post { listen(onPartial, onResult, preferOnDevice = false) }
                } else onResult(null)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        )
    }

    fun stopListening() {
        recognizer?.cancel()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val t = tts
        if (t == null || !ttsReady) { onDone?.let { main.post(it) }; return }
        val id = "hail-${System.nanoTime()}"
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) onDone?.let { main.post(it) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) onDone?.let { main.post(it) } }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun shutdown() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    companion object {
        val INDIA: Locale = Locale.forLanguageTag("en-IN")

        /** "₹40" reads badly aloud; "40 rupees" doesn't. */
        fun rupees(v: Double): String = "${Math.round(v)} rupees"
    }
}
