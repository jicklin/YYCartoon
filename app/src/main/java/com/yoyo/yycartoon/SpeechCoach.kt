package com.yoyo.yycartoon

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class SpeechCoach(context: Context, private val onReady: (Boolean) -> Unit) {
    private var textToSpeech: TextToSpeech? = null
    private var ready = false
    private val applicationContext = context.applicationContext

    init {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                textToSpeech?.language = Locale.CHINA
            }
            onReady(ready)
        }
    }

    fun speak(text: String) {
        if (!ready) return
        textToSpeech?.setSpeechRate(0.92f)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    fun stop() {
        textToSpeech?.stop()
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

}
