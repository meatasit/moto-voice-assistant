# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin metadata
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ── Gson (WebhookResponse uses @SerializedName) ──────────────────────────────
-keepattributes *Annotation*
-keep class com.google.gson.stream.** { *; }
-keep class com.moto.voice.network.** { *; }
-keep class com.moto.voice.network.WebhookResponse$* { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers,allowobfuscation class com.moto.voice.network.** {
    <init>(...);
    <fields>;
}

# ── OkHttp ────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── media3 / ExoPlayer ────────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── security-crypto (Tink under the hood) ────────────────────────────────────
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }

# ── App entry points (Services, Activities, VoiceInteractionSession) ─────────
-keep class com.moto.voice.service.** { *; }
-keep class com.moto.voice.media.FmPlayerService { *; }
-keep class com.moto.voice.VoiceAssistActivity { *; }

# Sealed classes / data classes used by pipeline
-keep class com.moto.voice.recognition.VoiceCommand { *; }
-keep class com.moto.voice.recognition.VoiceCommand$* { *; }

# History JSON — persisted, must survive R8
-keep class com.moto.voice.data.HistoryEntry { *; }
-keep class com.moto.voice.data.HistoryAction { *; }
-keep class com.moto.voice.data.HistoryAction$* { *; }

# Coroutines
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepclassmembers class kotlinx.coroutines.CoroutineExceptionHandler { *; }
