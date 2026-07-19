# Hilt / Dagger
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.** { *; }
-keep @javax.inject.Inject class *
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }
-keep class * extends dagger.hilt.android.internal.managers.FragmentComponentManager { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewModelComponentManager { *; }
-dontwarn dagger.internal.codegen.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**
-dontnote androidx.room.**

# Kotlin
-keep class kotlin.Metadata { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-dontwarn kotlinx.coroutines.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Parcelize
-keep class * implements android.os.Parcelable { *; }

# Encryption / Security
-keep class androidx.security.crypto.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Biometric
-keep class androidx.biometric.** { *; }

# ML Kit Face Detection
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Gson (FullScanOrchestrator)
-keep class com.google.gson.** { *; }

# JSON (PhoneGuardVpnService)
-keep class org.json.** { *; }

# Crypto / PBKDF2 (SecurityUtils)
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }

# WorkManager / Hilt Workers
-keep class androidx.work.** { *; }
-keep class * extends androidx.hilt.work.HiltWorker { *; }
-keep class com.phoneguard.worker.** { *; }

# PhoneGuard util & UI
-keep class com.phoneguard.util.** { *; }
-keep class com.phoneguard.ui.screens.antitheft.** { *; }
-keep class com.phoneguard.ui.screens.callblocker.** { *; }
-keep class com.phoneguard.ui.screens.firewall.** { *; }
-keep class com.phoneguard.ui.screens.settings.** { *; }
-keep class com.phoneguard.ui.screens.dashboard.** { *; }
-keep class com.phoneguard.ui.screens.simswap.** { *; }
-keep class com.phoneguard.ui.screens.privacyscanner.** { *; }
-keep class com.phoneguard.ui.screens.spywarecheck.** { *; }
-keep class com.phoneguard.ui.screens.fullscan.** { *; }
-keep class com.phoneguard.ui.screens.vault.** { *; }
-keep class com.phoneguard.vault.** { *; }
-keep class com.phoneguard.shouldersurfer.** { *; }
-keep class com.phoneguard.firewall.** { *; }
-keep class com.phoneguard.callblocker.** { *; }
-keep class com.phoneguard.fullscan.** { *; }
-keep class com.phoneguard.antitheft.** { *; }
-keep class com.phoneguard.spywarecheck.** { *; }
-keep class com.phoneguard.privacyscanner.** { *; }
-keep class com.phoneguard.data.repository.** { *; }
-keep class com.phoneguard.data.settings.** { *; }

# Keep model classes
-keep class com.phoneguard.model.** { *; }
-keep class com.phoneguard.data.local.** { *; }

# Navigation Compose
-keep class androidx.navigation.compose.** { *; }
-dontwarn androidx.navigation.compose.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ML Kit
-keep class com.google.mlkit.vision.face.** { *; }
-dontwarn com.google.mlkit.vision.face.**

# Optimization
-optimizationpasses 5
-allowaccessmodification
