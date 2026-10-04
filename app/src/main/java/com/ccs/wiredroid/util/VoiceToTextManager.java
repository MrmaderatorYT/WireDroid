package com.ccs.wiredroid.util;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import android.util.Log;

import java.util.ArrayList;

/**
 * Voice-to-text using Android SpeechRecognizer.
 * Works offline on devices with downloaded voice models.
 *
 * Features:
 * - Configurable recognition language (default "uk-UA").
 * - Continuous listening while enabled: on a final result or a recoverable
 *   error the recognizer restarts itself so the caller can collect a long
 *   dictated message. Call {@link #stopListening()} / {@link #destroy()} to stop.
 * - Tracks the timestamp of the last speech activity so callers can detect
 *   silence (e.g. auto-send after N seconds of quiet).
 */
public class VoiceToTextManager {

    public interface Callback {
        void onResult(String text);
        void onError(String error);
        void onPartialResult(String partial);
    }

    private final Context context;
    private SpeechRecognizer recognizer;
    private boolean isListening = false;
    private boolean continuous = false; // рестартити слухання після результату/помилки
    private Callback callback;
    private String language = "uk-UA";

    /** Час останньої мовної активності (onPartialResults / onResults), System.currentTimeMillis(). */
    private volatile long lastSpeechActivityMs = 0L;

    public VoiceToTextManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setLanguage(String language) {
        this.language = language == null ? "uk-UA" : language;
    }

    public String getLanguage() {
        return language;
    }

    /** Чи був якийсь мовний вхід з моменту старту (для детекту "тишина з самого початку"). */
    public long getLastSpeechActivityMs() {
        return lastSpeechActivityMs;
    }

    /**
     * Увімкнути безперервне слухання: після фінального результату або помилки
     * автоматично рестартує recogniser. Використовується для диктування довгих
     * повідомлень. За замовчуванням вимкнено (поведінка як раніше — одна сесія).
     */
    public void setContinuous(boolean continuous) {
        this.continuous = continuous;
    }

    public void startListening() {
        if (isListening) {
            Log.w("VTM", "startListening: already listening, skip");
            return;
        }

        boolean available = SpeechRecognizer.isRecognitionAvailable(context);
        Log.d("VTM", "startListening: recognitionAvailable=" + available);
        if (!available) {
            if (callback != null) {
                callback.onError("Speech recognition not available on this device");
            }
            return;
        }

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            Log.d("VTM", "startListening: created recognizer=" + (recognizer != null));
        }
        if (recognizer == null) {
            Log.e("VTM", "startListening: createSpeechRecognizer returned null!");
            if (callback != null) {
                callback.onError("Failed to create speech recognizer");
            }
            return;
        }
        recognizer.setRecognitionListener(createListener());

        Log.d("VTM", "startListening: calling recognizer.startListening()");
        recognizer.startListening(buildIntent());
    }

    private RecognitionListener createListener() {
        return new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                Log.d("VTM", "onReadyForSpeech");
            }

            @Override
            public void onBeginningOfSpeech() {
                lastSpeechActivityMs = System.currentTimeMillis();
                Log.d("VTM", "onBeginningOfSpeech");
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                lastSpeechActivityMs = System.currentTimeMillis();
                Log.d("VTM", "onEndOfSpeech: lastSpeechActivityMs=" + lastSpeechActivityMs);
            }

            @Override
            public void onError(int error) {
                Log.d("VTM", "onError: error=" + error + " continuous=" + continuous + " isListening=" + isListening);
                // In continuous mode, retry on recoverable errors (silence, pause, busy)
                if (continuous && shouldRetry(error)) {
                    Log.d("VTM", "onError: retrying (continuous mode)");
                    if (recognizer != null) {
                        try {
                            recognizer.startListening(buildIntent());
                        } catch (Throwable t) {
                            Log.e("VTM", "onError: restart failed: " + t.getMessage());
                        }
                    }
                    return;
                }
                isListening = false;
                String errorMsg = errorMessage(error);
                Log.d("VTM", "onError: reporting to callback: " + errorMsg);
                if (callback != null) callback.onError(errorMsg);
            }

            @Override
            public void onResults(Bundle results) {
                lastSpeechActivityMs = System.currentTimeMillis();
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                Log.d("VTM", "onResults: matches=" + (matches != null ? matches.size() : 0)
                        + " continuous=" + continuous);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    Log.d("VTM", "onResults: text='" + text + "'");
                    if (callback != null) callback.onResult(text);
                }
                // In continuous mode, restart for next phrase
                if (continuous && recognizer != null) {
                    Log.d("VTM", "onResults: restarting for next phrase");
                    try {
                        recognizer.startListening(buildIntent());
                    } catch (Throwable t) {
                        Log.e("VTM", "onResults: restart failed: " + t.getMessage());
                    }
                } else {
                    isListening = false;
                    Log.d("VTM", "onResults: not continuous, setting isListening=false");
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                Log.d("VTM", "onPartialResults: partial=" + (partial != null ? partial.size() : 0));
                if (partial != null && !partial.isEmpty()) {
                    lastSpeechActivityMs = System.currentTimeMillis();
                    String text = partial.get(0);
                    Log.d("VTM", "onPartialResults: text='" + text + "'");
                    if (callback != null) callback.onPartialResult(text);
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        };
    }

    private Intent buildIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return intent;
    }

    /** Чи варто рестартити слухання після цієї помилки (типа тиша/коротка пауза). */
    private static boolean shouldRetry(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return true;
            default:
                return false;
        }
    }

    private static String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No speech detected";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Speech timeout";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Recognition service bind failed — install or enable Google app";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission denied";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognition service busy";
            default:
                return "Error: " + error;
        }
    }

    public void stopListening() {
        continuous = false;
        if (recognizer != null) {
            try {
                recognizer.stopListening();
            } catch (Throwable ignored) {}
        }
        isListening = false;
    }

    public boolean isListening() {
        return isListening;
    }

    public void destroy() {
        continuous = false;
        isListening = false;
        if (recognizer != null) {
            try {
                recognizer.stopListening();
                recognizer.destroy();
            } catch (Throwable ignored) {}
            recognizer = null;
        }
    }
}
