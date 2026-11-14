package com.jarvis.assistant.audio

import kotlin.math.max

class VoiceBargeInDetector(
    private val headsetThreshold: Float = 0.25f,
    private val headsetFramesRequired: Int = 4,
    private val speakerThreshold: Float = 0.12f,
    private val speakerNoiseMultiplier: Float = 3f,
    private val speakerFramesRequired: Int = 8,
    private val speakerThresholdWithoutEchoCancellation: Float = 0.24f,
    private val speakerNoiseMultiplierWithoutEchoCancellation: Float = 4f,
    private val speakerFramesRequiredWithoutEchoCancellation: Int = 18
) {
    private var voicedFrames = 0

    val isTrackingSpeech: Boolean
        get() = voicedFrames > 0

    fun observe(
        amplitude: Float,
        ambientNoiseFloor: Float,
        headsetConnected: Boolean,
        echoCancellationEnabled: Boolean
    ): Boolean {
        val threshold = when {
            headsetConnected -> headsetThreshold
            echoCancellationEnabled -> max(speakerThreshold, ambientNoiseFloor * speakerNoiseMultiplier)
            else -> max(
                speakerThresholdWithoutEchoCancellation,
                ambientNoiseFloor * speakerNoiseMultiplierWithoutEchoCancellation
            )
        }
        val framesRequired = when {
            headsetConnected -> headsetFramesRequired
            echoCancellationEnabled -> speakerFramesRequired
            else -> speakerFramesRequiredWithoutEchoCancellation
        }
        voicedFrames = if (amplitude >= threshold) voicedFrames + 1 else 0
        if (voicedFrames < framesRequired) return false

        reset()
        return true
    }

    fun reset() {
        voicedFrames = 0
    }
}
