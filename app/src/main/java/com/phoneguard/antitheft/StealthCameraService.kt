package com.phoneguard.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.phoneguard.R
import com.phoneguard.data.local.IntrusionEventDao
import com.phoneguard.util.SmsSender
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stealth Camera Service — фото с камеры без превью.
 *
 * Запускается по SMS-команде `#PG:PIN:PHOTO` и из `IntrusionResponseExecutor`.
 *
 * Что было не так раньше:
 *  - в `CaptureRequest.Builder.addTarget(...)` передавался `SurfaceTexture`, а метод
 *    принимает `Surface` — файл просто не компилировался;
 *  - `ImageReader` создавался после запроса и подписывался на события без `Handler`
 *    с Looper'ом (слушатель на IO-потоке падал);
 *  - камера не закрывалась по таймауту, а `SurfaceTexture` — вообще никогда;
 *  - упавшая съёмка всё равно отправляла SMS «фото сделано».
 *
 * Теперь используется CameraX `ImageCapture` (уже подключён в проекте): он сам
 * управляет потоками, поворотом и закрытием устройства.
 */
@AndroidEntryPoint
class StealthCameraService : LifecycleService() {

    companion object {
        const val ACTION_TAKE_PHOTO = "com.phoneguard.antitheft.TAKE_PHOTO"
        const val EXTRA_SENDER = "sender_number"
        const val EXTRA_EVENT_ID = "event_id"

        /** Каталог фото-улик внутри `getExternalFilesDir(null)` (используется и уборкой данных). */
        const val OUTPUT_DIR = "phoneguard_stealth"

        private const val TAG = "StealthCameraService"
        private const val NOTIFICATION_ID = com.phoneguard.util.NotificationIds.STEALTH_CAMERA
        private const val CHANNEL_ID = "stealth_camera_channel"
        private const val CAPTURE_TIMEOUT_MS = 20_000L
    }

    @Inject
    lateinit var intrusionEventDao: IntrusionEventDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_TAKE_PHOTO) {
            val sender = intent.getStringExtra(EXTRA_SENDER)
            val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L).takeIf { it > 0 }
            startForeground(NOTIFICATION_ID, createNotification())
            serviceScope.launch {
                try {
                    takePhoto(sender, eventId)
                } catch (e: Exception) {
                    Log.w(TAG, "Stealth photo failed", e)
                } finally {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.stealth_camera_title),
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.stealth_camera_title))
            .setContentText(getString(R.string.stealth_camera_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private suspend fun takePhoto(sender: String?, eventId: Long?) {
        val outputDir = File(getExternalFilesDir(null), OUTPUT_DIR)
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.w(TAG, "Cannot create output dir $outputDir")
            return
        }
        val photoFile = File(outputDir, "stealth_${System.currentTimeMillis()}.jpg")

        val provider = awaitCameraProvider() ?: run {
            Log.w(TAG, "Camera provider unavailable")
            return
        }
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val bound = bindCamera(provider, imageCapture)
        if (!bound) {
            Log.w(TAG, "No usable camera")
            return
        }

        val saved = captureToFile(imageCapture, photoFile)
        provider.unbindAll()

        if (!saved) {
            photoFile.delete()
            Log.w(TAG, "Photo was not saved")
            return
        }

        eventId?.let { id ->
            runCatching { intrusionEventDao.updatePhotoPath(id, photoFile.absolutePath) }
                .onFailure { Log.w(TAG, "Cannot attach photo to event $id", it) }
        }

        // Подтверждение отправляем только если фото действительно сохранено.
        sender?.let { destination ->
            sendSms(destination, getString(R.string.stealth_photo_sent, photoFile.name))
        }
    }

    /**
     * Привязывает камеру: сначала заднюю, при её отсутствии — фронтальную.
     * `bindToLifecycle` бросает `IllegalArgumentException`, если селектору не
     * соответствует ни одна камера, поэтому перебор безопасен.
     */
    private fun bindCamera(provider: ProcessCameraProvider, imageCapture: ImageCapture): Boolean {
        val selectors = listOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA
        )
        selectors.forEach { selector ->
            val bound = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, imageCapture)
            }
            if (bound.isSuccess) return true
            Log.w(TAG, "Camera bind failed: ${bound.exceptionOrNull()?.message}")
        }
        return false
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider? =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(this@StealthCameraService)
                future.addListener(
                    {
                        val provider = runCatching { future.get() }.getOrNull()
                        if (cont.isActive) cont.resume(provider)
                    },
                    ContextCompat.getMainExecutor(this@StealthCameraService)
                )
            }
        }

    private suspend fun captureToFile(imageCapture: ImageCapture, file: File): Boolean =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(this@StealthCameraService),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.w(TAG, "ImageCapture failed: ${exception.message}", exception)
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                )
            }
        } ?: false

    private fun sendSms(destination: String, text: String) {
        // Общая точка отправки: учитывает длину сообщения, номер в E.164 и
        // отсутствие телефонии на устройстве.
        if (!SmsSender.send(this, destination, text)) Log.w(TAG, "Cannot send SMS")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
