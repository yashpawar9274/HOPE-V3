package com.jarvis.assistant.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.ui.home.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScreenRecordingState {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    ERROR
}

class ScreenRecordingService : Service() {

    companion object {
        private const val TAG = "ScreenRecordingService"
        private const val CHANNEL_ID = "hope_screen_recording"
        private const val NOTIFICATION_ID = 73
        private const val ACTION_START = "com.jarvis.assistant.action.START_SCREEN_RECORDING"
        private const val ACTION_STOP = "com.jarvis.assistant.action.STOP_SCREEN_RECORDING"
        private const val EXTRA_RESULT_CODE = "screen_capture_result_code"
        private const val EXTRA_RESULT_DATA = "screen_capture_result_data"
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_CHANNEL_COUNT = 2

        private val _state = MutableStateFlow(ScreenRecordingState.IDLE)
        val state: StateFlow<ScreenRecordingState> = _state.asStateFlow()

        private val _lastSavedName = MutableStateFlow("")
        val lastSavedName: StateFlow<String> = _lastSavedName.asStateFlow()

        private val _lastError = MutableStateFlow("")
        val lastError: StateFlow<String> = _lastError.asStateFlow()

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenRecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val muxerLock = Any()
    @Volatile private var stopRequested = false
    @Volatile private var captureError: Throwable? = null
    @Volatile private var isFinishing = false
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var outputName = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent, startId)
            ACTION_STOP -> finishRecording()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent, startId: Int) {
        if (_state.value == ScreenRecordingState.RECORDING ||
            _state.value == ScreenRecordingState.STARTING
        ) return

        _state.value = ScreenRecordingState.STARTING
        _lastSavedName.value = ""
        stopRequested = false
        captureError = null
        isFinishing = false
        _lastError.value = ""
        createNotificationChannel()
        try {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Internal app audio capture requires Android 10 or newer."
            }
            startAsForeground()
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            } ?: throw IllegalStateException("Android screen-capture approval was not received.")

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val activeProjection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Android did not grant screen-capture access.")
            projection = activeProjection

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getRealMetrics(metrics)
            val scale = (1080f / maxOf(metrics.widthPixels, metrics.heightPixels)).coerceAtMost(1f)
            val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) and 1.inv()
            val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) and 1.inv()

            prepareOutput()
            createMuxer()
            configureVideoEncoder(width, height)
            configureAudioCapture(activeProjection)
            configureAudioEncoder()

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    finishRecording()
                }
            }
            projectionCallback = callback
            activeProjection.registerCallback(callback, mainHandler)

            val encoderSurface = videoSurface
                ?: throw IllegalStateException("The video encoder surface is unavailable.")
            virtualDisplay = activeProjection.createVirtualDisplay(
                "HOPE Screen Recording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface,
                null,
                mainHandler
            ) ?: throw IllegalStateException("Android could not create a recording display.")

            videoEncoder?.start()
            audioEncoder?.start()
            audioRecord?.startRecording()
            check(audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android did not start internal audio capture."
            }
            _state.value = ScreenRecordingState.RECORDING
            updateNotification("Recording screen and supported app audio.")
            startEncoderWorkers()
        } catch (error: Exception) {
            Log.e(TAG, "Screen recording could not start.", error)
            _lastError.value = error.message ?: "Android could not start the screen recording."
            cleanupFailedRecording()
            _state.value = ScreenRecordingState.ERROR
            updateNotification("Recording failed. Tap HOPE to try again.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun prepareOutput() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outputName = "HOPE_Screen_$timestamp.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/HOPE"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("Android could not create the recording in Movies/HOPE.")
            outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: throw IllegalStateException("Android could not open the new recording file.")
        } else {
            val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "HOPE")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("HOPE could not create its Movies folder.")
            }
            outputFile = File(directory, outputName)
        }
    }

    private fun createMuxer() {
        val descriptor = outputDescriptor?.fileDescriptor
        muxer = if (descriptor != null) {
            MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(
                outputFile?.absolutePath
                    ?: throw IllegalStateException("The recording file could not be created."),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        }
    }

    private fun configureVideoEncoder(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 3).coerceIn(2_000_000, 10_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoSurface = encoder.createInputSurface()
    }

    private fun configureAudioCapture(activeProjection: MediaProjection) {
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) { "Android could not determine an internal audio buffer size." }
        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufferSize * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "Android could not initialize internal app audio capture."
        }
        audioRecord = record
    }

    private fun configureAudioEncoder() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun startEncoderWorkers() {
        videoThread = Thread(::drainVideoEncoder, "HopeScreenVideoEncoder").apply { start() }
        audioThread = Thread(::captureAndEncodeAudio, "HopeScreenAudioEncoder").apply { start() }
    }

    private fun drainVideoEncoder() {
        val encoder = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var outputEnded = false
        try {
            while (!outputEnded) {
                val index = encoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        registerMuxerTrack(isAudio = false, encoder.outputFormat)
                    index >= 0 -> {
                        outputEnded = writeEncodedSample(encoder, index, info, isAudio = false)
                    }
                }
            }
        } catch (error: Exception) {
            reportCaptureFailure(error)
        }
    }

    private fun captureAndEncodeAudio() {
        val record = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var audioEndQueued = false
        var outputEnded = false
        var audioPresentationTimeUs = System.nanoTime() / 1_000L
        var totalSamples = 0L
        try {
            while (!outputEnded) {
                if (!stopRequested && !audioEndQueued) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("The audio encoder input buffer is unavailable.")
                        inputBuffer.clear()
                        val bytesRead = record.read(
                            inputBuffer,
                            inputBuffer.capacity(),
                            AudioRecord.READ_BLOCKING
                        )
                        when {
                            bytesRead > 0 -> {
                                val samplesRead = bytesRead / (AUDIO_CHANNEL_COUNT * 2)
                                val presentationTime = audioPresentationTimeUs +
                                    totalSamples * 1_000_000L / AUDIO_SAMPLE_RATE
                                encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    bytesRead,
                                    presentationTime,
                                    0
                                )
                                totalSamples += samplesRead
                            }
                            bytesRead < 0 && stopRequested -> {
                                encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    audioPresentationTimeUs +
                                        totalSamples * 1_000_000L / AUDIO_SAMPLE_RATE,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                audioEndQueued = true
                            }
                            bytesRead == AudioRecord.ERROR_DEAD_OBJECT ->
                                throw IllegalStateException("Android stopped internal audio capture.")
                            bytesRead < 0 ->
                                throw IllegalStateException("Android could not read internal app audio ($bytesRead).")
                            else -> encoder.queueInputBuffer(inputIndex, 0, 0, audioPresentationTimeUs, 0)
                        }
                    }
                } else if (!audioEndQueued) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            audioPresentationTimeUs + totalSamples * 1_000_000L / AUDIO_SAMPLE_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        audioEndQueued = true
                    }
                }

                val index = encoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        registerMuxerTrack(isAudio = true, encoder.outputFormat)
                    index >= 0 -> outputEnded = writeEncodedSample(encoder, index, info, isAudio = true)
                }
            }
        } catch (error: Exception) {
            if (!stopRequested) reportCaptureFailure(error)
        }
    }

    private fun registerMuxerTrack(isAudio: Boolean, format: MediaFormat) {
        synchronized(muxerLock) {
            val activeMuxer = muxer ?: throw IllegalStateException("The MP4 muxer is unavailable.")
            if (isAudio) {
                check(audioTrack < 0) { "The audio encoder format changed more than once." }
                audioTrack = activeMuxer.addTrack(format)
            } else {
                check(videoTrack < 0) { "The video encoder format changed more than once." }
                videoTrack = activeMuxer.addTrack(format)
            }
            if (!muxerStarted && audioTrack >= 0 && videoTrack >= 0) {
                activeMuxer.start()
                muxerStarted = true
            }
        }
    }

    private fun writeEncodedSample(
        encoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        isAudio: Boolean
    ): Boolean {
        val outputBuffer = encoder.getOutputBuffer(index)
        try {
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                synchronized(muxerLock) {
                    val track = if (isAudio) audioTrack else videoTrack
                    if (!muxerStarted || track < 0) {
                        throw IllegalStateException("MP4 tracks were not ready when encoded media arrived.")
                    }
                    val sample = outputBuffer
                        ?: throw IllegalStateException("The encoded media buffer is unavailable.")
                    sample.position(info.offset)
                    sample.limit(info.offset + info.size)
                    muxer?.writeSampleData(track, sample, info)
                        ?: throw IllegalStateException("The MP4 muxer is unavailable.")
                }
            }
            return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        } finally {
            encoder.releaseOutputBuffer(index, false)
        }
    }

    private fun reportCaptureFailure(error: Throwable) {
        if (stopRequested || captureError != null) return
        captureError = error
        _lastError.value = error.message ?: "Android stopped screen or app-audio capture."
        Log.e(TAG, "Screen/audio capture failed.", error)
        stopRequested = true
        mainHandler.post { finishRecording() }
    }

    private fun finishRecording() {
        if (isFinishing) return
        isFinishing = true
        _state.value = ScreenRecordingState.STOPPING
        stopRequested = true
        Thread(::finalizeRecording, "HopeScreenRecordingFinalizer").start()
    }

    private fun finalizeRecording() {
        try {
            try {
                audioRecord?.stop()
            } catch (error: Exception) {
                Log.d(TAG, "Internal audio capture was already stopped.", error)
            }
            try {
                virtualDisplay?.release()
            } catch (error: Exception) {
                captureError = captureError ?: error
                _lastError.value = error.message ?: "Android could not release the recording display."
                Log.w(TAG, "Could not release the recording display.", error)
            }
            virtualDisplay = null
            try {
                videoEncoder?.signalEndOfInputStream()
            } catch (error: Exception) {
                Log.w(TAG, "Could not signal the video encoder to finish.", error)
            }

            joinWorker(videoThread)
            joinWorker(audioThread)

            var saved = false
            synchronized(muxerLock) {
                val activeMuxer = muxer
                if (activeMuxer != null && muxerStarted &&
                    videoThread?.isAlive != true && audioThread?.isAlive != true
                ) {
                    try {
                        activeMuxer.stop()
                        saved = true
                    } catch (error: Exception) {
                        Log.e(TAG, "The completed audio/video recording could not be finalized.", error)
                    }
                }
            }

            releaseCaptureResources()
            if (saved) {
                try {
                    publishRecording()
                    if (captureError == null) {
                        _state.value = ScreenRecordingState.IDLE
                        updateNotification("Saved screen recording with supported app audio.")
                    } else {
                        _state.value = ScreenRecordingState.ERROR
                        updateNotification("Recording ended early. A partial video was saved.")
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Could not publish the completed screen recording.", error)
                    deleteIncompleteRecording()
                    _state.value = ScreenRecordingState.ERROR
                }
            } else {
                deleteIncompleteRecording()
                _state.value = ScreenRecordingState.ERROR
                updateNotification("Recording could not be saved. Check Android audio-capture support.")
            }

            projectionCallback?.let { callback ->
                try {
                    projection?.unregisterCallback(callback)
                } catch (error: Exception) {
                    Log.w(TAG, "Could not unregister the projection callback.", error)
                }
            }
            try {
                projection?.stop()
            } catch (error: Exception) {
                Log.w(TAG, "Could not stop the screen-capture session.", error)
            }
            projectionCallback = null
            projection = null
        } finally {
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isFinishing = false
            }
        }
    }

    private fun joinWorker(thread: Thread?) {
        if (thread == null || thread == Thread.currentThread()) return
        try {
            thread.join(5_000L)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while waiting for the recording encoder.", error)
        }
        if (thread.isAlive) {
            Log.e(TAG, "Recording encoder did not finish before the shutdown timeout.")
        }
    }

    private fun releaseCaptureResources() {
        try {
            audioRecord?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Could not release internal audio capture.", error)
        }
        audioRecord = null

        try {
            videoEncoder?.stop()
        } catch (error: Exception) {
            Log.d(TAG, "Video encoder was not running during cleanup.", error)
        }
        try {
            videoEncoder?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Could not release the video encoder.", error)
        }
        videoEncoder = null

        try {
            audioEncoder?.stop()
        } catch (error: Exception) {
            Log.d(TAG, "Audio encoder was not running during cleanup.", error)
        }
        try {
            audioEncoder?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Could not release the audio encoder.", error)
        }
        audioEncoder = null

        try {
            videoSurface?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Could not release the video input surface.", error)
        }
        videoSurface = null

        try {
            muxer?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Could not release the MP4 muxer.", error)
        }
        muxer = null
        muxerStarted = false

        try {
            outputDescriptor?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Could not close the recording file.", error)
        }
        outputDescriptor = null
    }

    private fun cleanupFailedRecording() {
        isFinishing = true
        stopRequested = true
        try {
            audioRecord?.stop()
        } catch (error: Exception) {
            Log.d(TAG, "Audio capture had not started during cleanup.", error)
        }
        try {
            virtualDisplay?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Could not release the recording display.", error)
        }
        virtualDisplay = null
        joinWorker(videoThread)
        joinWorker(audioThread)
        releaseCaptureResources()
        projectionCallback?.let { callback ->
            try {
                projection?.unregisterCallback(callback)
            } catch (error: Exception) {
                Log.w(TAG, "Could not unregister failed projection callback.", error)
            }
        }
        try {
            projection?.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Could not stop projection after recording setup failed.", error)
        }
        projection = null
        projectionCallback = null
        deleteIncompleteRecording()
        isFinishing = false
    }

    private fun publishRecording() {
        val savedName = outputUri?.let { uri ->
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            check(contentResolver.update(uri, values, null, null) > 0) {
                "Android could not publish the completed screen recording."
            }
            "Movies/HOPE/$outputName"
        } ?: outputFile?.absolutePath
        _lastSavedName.value = savedName.orEmpty()
        Log.i(TAG, "Screen recording saved: $savedName")
    }

    private fun deleteIncompleteRecording() {
        outputUri?.let { uri ->
            try {
                contentResolver.delete(uri, null, null)
            } catch (error: Exception) {
                Log.e(TAG, "Could not remove the incomplete recording.", error)
            }
        }
        outputFile?.let { file ->
            if (file.exists() && !file.delete()) {
                Log.e(TAG, "Could not remove incomplete recording file: ${file.absolutePath}")
            }
        }
        outputUri = null
        outputFile = null
    }

    private fun startAsForeground() {
        val notification = buildNotification("Preparing screen and app-audio recording.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("HOPE screen recording")
            .setContentText(message)
            .setContentIntent(openAppIntent)
            .setOngoing(_state.value == ScreenRecordingState.RECORDING ||
                _state.value == ScreenRecordingState.STARTING ||
                _state.value == ScreenRecordingState.STOPPING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when HOPE is recording the phone screen and supported app audio."
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (_state.value == ScreenRecordingState.RECORDING ||
            _state.value == ScreenRecordingState.STARTING
        ) {
            finishRecording()
        }
        super.onDestroy()
    }
}
