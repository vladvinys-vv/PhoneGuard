package com.phoneguard.shouldersurfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.phoneguard.R
import com.phoneguard.util.NotificationChannels
import com.phoneguard.util.NotificationChannelInstaller
import com.phoneguard.util.BatteryOptimizationHelper
import com.phoneguard.util.RetryBackoffPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class ShoulderSurferService : LifecycleService() {

    @Inject
    lateinit var batteryOptimizationHelper: BatteryOptimizationHelper

    @Inject
    lateinit var preferencesManager: com.phoneguard.data.preferences.PreferencesManager

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analysisJob: Job? = null

    /** Привязанный анализатор: нужен, чтобы снять его при остановке. */
    private var boundAnalysis: ImageAnalysis? = null
    private var lastDetectionTime = 0L
    private var lastAlertTime = 0L
    private var adaptiveIntervalMs = 2000L

    /**
     * Делегат вынесен отдельно, чтобы `onDestroy` мог проверить `isInitialized()`.
     *
     * Раньше там стоял `faceDetector.close()`: `by lazy` **создавал** детектор прямо в
     * `onDestroy`, даже если сервис ни разу не анализировал кадр (камера недоступна, сразу
     * выключили) — лишняя инициализация ML Kit на выходе.
     */
    private val faceDetectorDelegate = lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                // Контуры не нужны (и заметно дороже): для «смотрит в экран» достаточно
                // углов поворота головы и вероятности открытых глаз.
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
    }

    private val faceDetector by faceDetectorDelegate

    companion object {
        private const val TAG = "ShoulderSurfer"

        private val CHANNEL_ID: String = NotificationChannels.Spec.SHOULDER_SURFER.channelId
        private const val NOTIFICATION_ID = com.phoneguard.util.NotificationIds.SHOULDER_SURFER_FOREGROUND
        private val ALERT_CHANNEL_ID: String =
            NotificationChannels.Spec.SHOULDER_SURFER_ALERT.channelId
        private const val ALERT_NOTIFICATION_ID = com.phoneguard.util.NotificationIds.SHOULDER_SURFER_ALERT
        const val ACTION_START = "com.phoneguard.shouldersurfer.START"
        const val ACTION_STOP = "com.phoneguard.shouldersurfer.STOP"

        /**
         * Запускает детектор «подглядывающего».
         *
         * @return `false`, если система запретила запуск foreground-сервиса из фона
         *         (Android 12+, `ForegroundServiceStartNotAllowedException`) или сервис
         *         недоступен — вызывающая сторона не должна показывать «включено».
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, ShoulderSurferService::class.java).apply {
                action = ACTION_START
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Shoulder surfer service start rejected by system", e)
                false
            }
        }

        fun stop(context: Context): Boolean {
            val intent = Intent(context, ShoulderSurferService::class.java).apply {
                action = ACTION_STOP
            }
            return try {
                context.startService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Shoulder surfer service stop failed", e)
                false
            }
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
        NotificationChannelInstaller.install(
            this,
            NotificationChannels.Spec.SHOULDER_SURFER,
            getString(R.string.shoulder_surfer_channel_name),
            getString(R.string.shoulder_surfer_channel_desc)
        )
        NotificationChannelInstaller.install(
            this,
            NotificationChannels.Spec.SHOULDER_SURFER_ALERT,
            getString(R.string.shoulder_surfer_alert_channel_name),
            getString(R.string.shoulder_surfer_alert_channel_desc)
        )
    }

    private fun createNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.shoulder_surfer_active_title))
            .setContentText(getString(R.string.shoulder_surfer_active_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startDetection() {
        if (analysisJob?.isActive == true) return
        adaptiveIntervalMs = batteryOptimizationHelper.getAdaptiveShoulderSurferInterval()

        analysisJob = lifecycleScope.launch {
            var failures = 0
            while (true) {
                try {
                    bindCamera()
                    Log.d(TAG, "Camera bound for shoulder surfer detection, interval=${adaptiveIntervalMs}ms")
                    return@launch
                } catch (e: Exception) {
                    val kind = classifyCameraFailure(e)
                    val decision = RetryBackoffPolicy.decide(failures, kind)
                    Log.w(
                        TAG,
                        "Camera bind failed (${failures + 1}/${RetryBackoffPolicy.MAX_ATTEMPTS}, $kind)",
                        e
                    )
                    if (!decision.shouldRetry) {
                        // Повторять бессмысленно (нет разрешения/камеры) или попытки кончились.
                        // Раньше сервис оставался «активным» с нерабочей камерой: висело
                        // постоянное уведомление и включённый переключатель. Врать в интерфейсе
                        // хуже, чем выключиться.
                        disableFeatureAndStop()
                        return@launch
                    }
                    failures++
                    delay(decision.delayMs)
                }
            }
        }
    }

    /** Собственно привязка камеры; вынесена, чтобы повторять её в цикле. */
    private suspend fun bindCamera() {
        val cameraProvider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(this@ShoulderSurferService).get()
        }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(320, 240))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(cameraExecutor, ::analyzeImage) }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            this@ShoulderSurferService,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            imageAnalyzer
        )
        boundAnalysis = imageAnalyzer
    }

    /**
     * Повторять ли попытку.
     *
     * `SecurityException` — нет разрешения, `IllegalArgumentException` — нет фронтальной камеры:
     * повтор не поможет никогда (правило — в [RetryBackoffPolicy]). Остальное (камера занята,
     * провайдер не готов) проходит само.
     */
    private fun classifyCameraFailure(error: Exception): RetryBackoffPolicy.FailureKind = when (error) {
        is SecurityException -> RetryBackoffPolicy.FailureKind.PERMANENT
        is IllegalArgumentException -> RetryBackoffPolicy.FailureKind.PERMANENT
        else -> RetryBackoffPolicy.FailureKind.TRANSIENT
    }

    private fun stopDetection() {
        analysisJob?.cancel()
        // Раньше предупреждение «кто-то смотрит в экран» оставалось висеть после остановки
        // детектора: `setAutoCancel(true)` убирает его только по тапу пользователя.
        NotificationChannelInstaller.cancel(
            this,
            NotificationChannels.cancelledIds(NotificationChannels.StopReason.DETECTION_STOPPED)
        )
        // Анализатор снимаем до `unbindAll()`: иначе в очереди камеры остаются кадры,
        // которые после остановки сервиса продолжают вызывать `analyzeImage`.
        runCatching { boundAnalysis?.clearAnalyzer() }
        boundAnalysis = null
        // Раньше здесь стоял `ProcessCameraProvider.getInstance(this).get()` прямо в
        // main-потоке: если провайдер камеры ещё инициализировался (а останавливать
        // слежку могут сразу после старта), главный поток ждал — вплоть до ANR.
        // Теперь развязка асинхронная, как в StealthCameraService.
        val future = runCatching { ProcessCameraProvider.getInstance(this) }.getOrNull()
        if (future == null) {
            Log.e(TAG, "Camera provider unavailable while stopping")
            return
        }
        future.addListener(
            {
                runCatching { future.get().unbindAll() }
                    .onFailure { Log.e(TAG, "Failed to stop camera", it) }
            },
            ContextCompat.getMainExecutor(this)
        )
        Log.d(TAG, "Shoulder surfer detection stopped")
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        // Интервалы — по монотонным часам (правила — в ShoulderSurferDetectionPolicy).
        val currentTime = SystemClock.elapsedRealtime()
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
                    lastDetectionTime = SystemClock.elapsedRealtime()
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
            // Пороги — в [ShoulderSurferDetectionPolicy] (там же их тесты).
            val suspicious = ShoulderSurferDetectionPolicy.isSuspiciousFace(
                pitchDegrees = face.headEulerAngleX,
                yawDegrees = face.headEulerAngleY,
                leftEyeProbability = face.leftEyeOpenProbability,
                rightEyeProbability = face.rightEyeOpenProbability
            )
            if (suspicious) {
                onSurferDetected()
                return
            }
        }
    }

    private fun onSurferDetected() {
        Log.w(TAG, "Potential shoulder surfer detected!")
        notifySurferDetected()
    }

    private fun notifySurferDetected() {
        // Раньше уведомление приходило на каждый кадр с лицом (раз в ~2 секунды) —
        // телефон буквально «пищал» постоянно. Теперь не чаще раза в 30 секунд.
        val now = SystemClock.elapsedRealtime()
        if (!ShoulderSurferDetectionPolicy.shouldAlert(now, lastAlertTime)) return
        lastAlertTime = now

        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, com.phoneguard.ui.MainActivity::class.java)
        // CLEAR_TASK перезапускал приложение с потерей состояния; SINGLE_TOP просто
        // поднимает уже открытый экран наверх.
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP

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

        // POST_NOTIFICATIONS (Android 13+): без разрешения уведомление не показывается, а
        // вызов может бросить SecurityException из ML Kit-колбэка и уронить сервис.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS не выдано — предупреждение о подглядывании не показывается")
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Не удалось показать предупреждение о подглядывании", it) }
    }

    /**
     * Камера недоступна: гасим сервис и снимаем флаг функции.
     *
     * Флаг обязателен: экран настроек показывает переключатель из настроек, и без сброса
     * пользователь видел «включено» у неработающего детектора.
     */
    private fun disableFeatureAndStop() {
        lifecycleScope.launch {
            runCatching { preferencesManager.setShoulderSurferEnabled(false) }
                .onFailure { Log.w(TAG, "Cannot disable shoulder surfer setting", it) }
        }
        stopDetection()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        cameraExecutor.shutdown()
        // Детектор закрываем только если он реально создавался (см. faceDetectorDelegate).
        if (faceDetectorDelegate.isInitialized()) {
            runCatching { faceDetector.close() }
        }
    }
}
