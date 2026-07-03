package com.phoneguard.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.telephony.SmsManager
import androidx.camera.core.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Stealth Camera Service.
 * Делает фото с задней камеры без показа превью.
 * Используется при SMS-команде #PG:PIN:PHOTO.
 */
@AndroidEntryPoint
class StealthCameraService : LifecycleService() {

    companion object {
        const val ACTION_TAKE_PHOTO = "com.phoneguard.antitheft.TAKE_PHOTO"
        const val EXTRA_SENDER = "sender_number"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "stealth_camera_channel"
        private const val OUTPUT_DIR = "phoneguard_stealth"
    }

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_TAKE_PHOTO) {
            val sender = intent.getStringExtra(EXTRA_SENDER)
            startForeground(NOTIFICATION_ID, createNotification())
            serviceScope.launch {
                takePhoto(sender)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "PhoneGuard Anti-Theft",
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneGuard Anti-Theft")
            .setContentText("Camera service running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private suspend fun takePhoto(sender: String?) = suspendCancellableCoroutine<Unit> { cont ->
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: run {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }

            val outputDir = File(getExternalFilesDir(null), OUTPUT_DIR)
            if (!outputDir.exists()) outputDir.mkdirs()
            val photoFile = File(outputDir, "stealth_${System.currentTimeMillis()}.jpg")

            // Используем Camera2 API напрямую — не нужно preview
            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureRequestBuilder.addTarget(
                            android.view.SurfaceTexture(0).apply { setDefaultBufferSize(1920, 1080) }
                        )

                        // Для простоты: используем ImageReader
                        val imageReader = android.media.ImageReader.newInstance(
                            1920, 1080, ImageFormat.JPEG, 1
                        )

                        val surfaces = listOf(imageReader.surface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                val captureRequest = captureRequestBuilder.build()
                                session.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {}, null)

                                imageReader.setOnImageAvailableListener({ reader ->
                                    val image = reader.acquireNextImage()
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)

                                    FileOutputStream(photoFile).use { it.write(bytes) }
                                    image.close()
                                    reader.close()
                                    session.close()
                                    camera.close()

                                    // Отправляем SMS с подтверждением
                                    sender?.let { s ->
                                        sendSms(s, "PhoneGuard: Фото сделано. Файл: ${photoFile.name}")
                                    }

                                    if (cont.isActive) cont.resume(Unit)
                                }, null)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                camera.close()
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }, null)
                    } catch (e: Exception) {
                        camera.close()
                        if (cont.isActive) cont.resume(Unit)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, null)

            // Timeout 30 секунд
            Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (cont.isActive) cont.resume(Unit)
            }, 30000)

        } catch (e: Exception) {
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private fun sendSms(destination: String, text: String) {
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(destination, null, text, null, null)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        serviceScope.cancel()
    }
}
