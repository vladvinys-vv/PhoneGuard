package com.phoneguard.shouldersurfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.phoneguard.R
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class ShoulderSurferService : LifecycleService() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analysisJob: Job? = null
    private var lastDetectionTime = 0L
    private val detectionIntervalMs = 2000L // Analyze every 2 seconds to save battery

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
    }

    companion object {
        private const val TAG = "ShoulderSurfer"
        private const val CHANNEL_ID = "shoulder_surfer_channel"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.phoneguard.shouldersurfer.START"
        const val ACTION_STOP = "com.phoneguard.shouldersurfer.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ShoulderSurferService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShoulderSurferService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startDetection()
            }
            ACTION_STOP -> {
                stopDetection()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        cameraExecutor.shutdown()
        faceDetector.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Защита от подглядывания",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Мониторинг surroundings для обнаружения подглядывания"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Защита от подглядывания активна")
            .setContentText("Камера анализирует окружение...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startDetection() {
        if (analysisJob?.isActive == true) return

        analysisJob = lifecycleScope.launch {
            try {
                val cameraProvider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(this@ShoulderSurferService).get()
                }

                val preview = Preview.Builder().build()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ::analyzeImage) }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this@ShoulderSurferService,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                Log.d(TAG, "Camera bound for shoulder surfer detection")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
            }
        }
    }

    private fun stopDetection() {
        analysisJob?.cancel()
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop camera", e)
        }
        Log.d(TAG, "Shoulder surfer detection stopped")
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDetectionTime < detectionIntervalMs) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = try {
                InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            } catch (e: Exception) {
                imageProxy.close()
                return
            }

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    lastDetectionTime = System.currentTimeMillis()
                    handleFaces(faces)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun handleFaces(faces: List<com.google.mlkit.vision.face.Face>) {
        if (faces.isEmpty()) return

        for (face in faces) {
            // Heuristic: face is likely looking at screen if:
            // 1. Euler X (pitch) is within -20 to +20 degrees (head not tilted up/down)
            // 2. Euler Y (yaw) is within -30 to +30 degrees (head not turned away)
            // 3. Eyes are open (if classification available)
            val pitch = face.headEulerAngleX // -180..180, 0 = facing camera
            val yaw = face.headEulerAngleY   // -180..180, 0 = facing camera

            val isFacingScreen = pitch in -20f..20f && yaw in -30f..30f

            if (isFacingScreen) {
                // Check if eyes are open (ML Kit provides this when CLASSIFICATION_MODE_ALL)
                val leftEyeOpen = face.leftEyeOpenProbability ?: 1.0f
                val rightEyeOpen = face.rightEyeOpenProbability ?: 1.0f
                val eyesOpen = leftEyeOpen > 0.3f && rightEyeOpen > 0.3f

                if (eyesOpen) {
                    onSurferDetected(face)
                    return // Only handle first surfer
                }
            }
        }
    }

    private fun onSurferDetected(face: com.google.mlkit.vision.face.Face) {
        Log.w(TAG, "Potential shoulder surfer detected!")
        // TODO: Trigger UI warning, blur screen, capture photo, send notification
        // For now, just log it
    }
}
