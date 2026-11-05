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

# JSON (PhoneGuardVpnService)
-keep class org.json.** { *; }

# Crypto / PBKDF2 (SecurityUtils)
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }

# WorkManager / Hilt Workers
-keep class androidx.work.** { *; }
-keep class * extends androidx.hilt.work.HiltWorker { *; }
# Имена своих Worker'ов, а не их содержимое: WorkManager хранит имя класса строкой в своей
# базе (`worker_class_name`) и создаёт объект по имени. Если R8 переименует класс, задачи,
# поставленные прошлой версией приложения (расписание сканирований, обновление фидов),
# после обновления не найдутся и будут молча выброшены. Тела методов и поля при этом
# сжимаются и переименовываются как обычно.
-keepnames class com.phoneguard.worker.**

# PhoneGuard: собственный код целиком НЕ прикрывается.
#
# Раньше здесь стояло около двадцати правил `-keep class com.phoneguard.<пакет>.** { *; }`
# — на все пакеты подряд. В итоге под `-keep` уходило 183 класса из 252: R8 не сжимал и не
# переименовывал почти ничего, и релиз отличался от debug размером, а не защитой.
# Эти правила не нужны, потому что R8 и так сохраняет всё достижимое от точек входа:
#
#   * Activity/Service/Receiver/Provider перечислены в манифесте — AGP генерирует для них
#     keep-правила сам, вручную их дублировать нельзя;
#   * Hilt и Room генерируют код и приносят свои consumer-правила (они выше в этом файле);
#   * Compose не обращается к нашим классам по имени;
#   * `Class.forName` в проекте ровно один — `android.os.SystemProperties` (платформа, R8 её
#     не переименовывает), а JSON пишется вручную (`util/ScanReportJson`), без Gson.
#
# Проверяет это `python3 tools/proguard_check.py`: он падает, если в коде появится рефлексия
# по собственному классу без прикрывающего правила, и ругается, если `-keep` снова начнёт
# прикрывать заметную долю своих классов. Реальный `mapping.txt` из Codemagic проверяется
# тем же скриптом: `python3 tools/proguard_check.py --mapping app/build/outputs/mapping/release/mapping.txt`.

# Имена констант enum обязаны выжить: они уходят в базу и в файл настроек строкой.
#
# `data/local/Converters` пишет `enum.name` в SQLite — категорию файла сейфа, тип и
# направление соединения брандмауэра, тип и важность вторжения; `util/SettingsTransfer`
# пишет `PreferencesKey.name` в переносимый файл настроек. Без этого правила R8
# переименовывает константы (`UNKNOWN` -> `a`), и строки, записанные прошлой версией
# приложения, перестают читаться: категория каждого файла сейфа становится `OTHER`,
# направление каждого соединения — `UNKNOWN`, важность вторжения — `LOW`. Молча:
# конвертеры специально трактуют неизвестное значение как «неизвестно», а не падают.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Navigation Compose
-keep class androidx.navigation.compose.** { *; }
-dontwarn androidx.navigation.compose.**

# SQLCipher (net.zetetic.database.sqlcipher) + старый пакет на случай переходного периода
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.sqlcipher.**
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ML Kit
-keep class com.google.mlkit.vision.face.** { *; }
-dontwarn com.google.mlkit.vision.face.**

# apk-parser (локальный анализ подписей APK)
-keep class net.dongliu.apk.parser.** { *; }
-keep class net.dongliu.commons.io.** { *; }
-dontwarn net.dongliu.apk.parser.**
-dontwarn net.dongliu.commons.**

# libphonenumber (нормализация номеров, офлайн metadata)
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**

# OkHttp / Okio (проверка доменов threat intel)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# R8 сохраняет подписи обобщённых типов: без `Signature` Gson/Room не восстанавливают
# generic-поля (`List<T>`, `Map<K,V>`) после обфускации — падение уже в рантайме.
# `*Annotation*` нужен Retrofit-подобным и Hilt-генерируемым классам (аннотации читаются
# рефлексией), `EnclosingMethod`/`InnerClasses` — для вложенных классов и лямбд.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# JSR-305 аннотации (`Nullable`, `Nonnull`) приходят из зависимостей как compileOnly —
# без `-dontwarn` R8 считает их отсутствие ошибкой и валит `assembleProductionRelease`.
-dontwarn javax.annotation.**

# Optimization
-optimizationpasses 5
-allowaccessmodification
