package com.phoneguard.shouldersurfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import com.phoneguard.util.BatteryOptimizationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class ShoulderSurferService : LifecycleService() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var batteryOptimizationHelper: BatteryOptimizationHelper

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analysisJob: Job? = null
    private var lastDetectionTime = 0L
    private var adaptiveIntervalMs = 2000L

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
        private const val ALERT_CHANNEL_ID = "shoulder_surfer_alert_channel"
        private const val ALERT_NOTIFICATION_ID = 1003
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
                try {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startDetection()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start shoulder surfer service", e)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopDetection()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Защита от подглядывания",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Мониторинг окружения для обнаружения подглядывания"
            }
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Оповещения о подглядывании",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления при обнаружении посторонних за экраном"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(alertChannel)
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
        adaptiveIntervalMs = batteryOptimizationHelper.getAdaptiveShoulderSurferInterval()

        analysisJob = lifecycleScope.launch {
            try {
                val cameraProvider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(this@ShoulderSurferService).get()
                }

                val preview = Preview.Builder().build()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(320, 240))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
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

                Log.d(TAG, "Camera bound for shoulder surfer detection, interval=${adaptiveIntervalMs}ms")
            } catch (e: SecurityException) {
                Log.e(TAG, "Camera permission denied, stopping service", e)
                stopSelf()
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
        if (currentTime - lastDetectionTime < adaptiveIntervalMs) {
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
            val pitch = face.headEulerAngleX
            val yaw = face.headEulerAngleY

            val isFacingScreen = pitch in -20f..20f && yaw in -30f..30f

            if (isFacingScreen) {
                val leftEyeOpen = face.leftEyeOpenProbability ?: 1.0f
                val rightEyeOpen = face.rightEyeOpenProbability ?: 1.0f
                val eyesOpen = leftEyeOpen > 0.3f && rightEyeOpen > 0.3f

                if (eyesOpen) {
                    onSurferDetected(face)
                    return
                }
            }
        }
    }

    private fun onSurferDetected(face: com.google.mlkit.vision.face.Face) {
        Log.w(TAG, "Potential shoulder surfer detected!")
        notifySurferDetected()
    }

    private fun notifySurferDetected() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, com.phoneguard.ui.MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.shoulder_surfer_notification_title))
            .setContentText(getString(R.string.shoulder_surfer_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        cameraExecutor.shutdown()
        faceDetector.close()
    }
}
