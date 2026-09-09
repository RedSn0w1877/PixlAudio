# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# Keep javax.lang.model classes (often needed by annotation processors or code generation libraries)
-keep class javax.lang.model.** { *; }
-keep interface javax.lang.model.** { *; }

# Keep javax.sound.sampled classes (for audio processing libraries like JFLAC)
-keep class javax.sound.sampled.** { *; }
-keep interface javax.sound.sampled.** { *; }

# Specific rules for JavaPoet if the above is not enough
-keep class com.squareup.javapoet.** { *; }
-keep interface com.squareup.javapoet.** { *; }

# Specific rules for AutoValue if it's directly used or a transitive dependency
# (though usually AutoValue is a compile-time dependency and shouldn't need this)
# -keep class com.google.auto.value.** { *; }
# -keep interface com.google.auto.value.** { *; }

# Rules for TagLib
-keep class com.kyant.taglib.** { *; }

# Rules for JAudioTagger (fallback metadata reader)
-keep class org.jaudiotagger.** { *; }

# [NUEVO] Regla general para mantener metadatos de Kotlin, puede ayudar a R8
-keep class kotlin.Metadata { *; }

# ExoPlayer FFmpeg extension
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.exoplayer.ffmpeg.** { *; }

# ExoPlayer MIDI extension and JSyn synthesizer
-keep class androidx.media3.decoder.midi.** { *; }
-keep class com.jsyn.** { *; }
-keep class com.softsynth.** { *; }
-dontwarn com.jsyn.**
-dontwarn com.softsynth.**

# Mantener clases de datos y sus miembros para evitar que R8 Full elimine campos
-keepclassmembers class com.theveloper.pixelplay.data.model.** { *; }
-keepclassmembers class com.theveloper.pixelplay.domain.model.** { *; }

# =============================================================================
# SERIALIZED DTOs — Gson (@SerializedName) and kotlinx.serialization (@Serializable)
# =============================================================================
# These rules cover the *network* DTO packages, which the two -keepclassmembers rules above
# do NOT: they only match data.model/domain.model. Gson builds these types reflectively, so
# once R8 strips their fields and synthetic no-arg constructor, deserialization dies at
# runtime with "Failed to invoke constructor '<X>()' with no args" + NPE. That is release-only
# — debug never runs R8 — and it is what broke Spotify import
# (com.theveloper.pixelplay.data.network.spotify.SpotifyAlbumRef was the first casualty).
-keep class com.theveloper.pixelplay.data.network.** { *; }
-keepclassmembers class com.theveloper.pixelplay.data.network.** {
    <fields>;
    <init>(...);
}

# Catch-all so a DTO added outside those packages later can't reintroduce the same bug.
-keepclasseswithmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# kotlinx.serialization: the compiler plugin emits `$$serializer` objects and `Companion`
# holders that are looked up by name. Nothing in this file kept them before.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.theveloper.pixelplay.**$$serializer { *; }
-keepclassmembers class com.theveloper.pixelplay.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.theveloper.pixelplay.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit: service interfaces are implemented via reflective proxies and their generic
# return types must survive for the converter to resolve the right DTO.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-keepattributes Signature, InnerClasses, EnclosingMethod, AnnotationDefault, *Annotation*

# Cast framework classes loaded via manifest/reflective entry points.
-keep class com.theveloper.pixelplay.data.service.cast.CastOptionsProvider { *; }
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider

# Gson generic type capture for backup/restore in release builds.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.theveloper.pixelplay.data.preferences.PreferenceBackupEntry { *; }
-keep class com.theveloper.pixelplay.data.backup.model.** { *; }
-keep class com.theveloper.pixelplay.data.backup.module.** { *; }
# Backup payload entities are part of the persisted .pxpl contract.
-keep class com.theveloper.pixelplay.data.database.FavoritesEntity { *; }
-keep class com.theveloper.pixelplay.data.database.SongEngagementEntity { *; }
-keep class com.theveloper.pixelplay.data.database.LyricsEntity { *; }
-keep class com.theveloper.pixelplay.data.database.SearchHistoryEntity { *; }
-keep class com.theveloper.pixelplay.data.database.TransitionRuleEntity { *; }

# Netty channel classes are instantiated reflectively and require public no-arg constructors.
# Without these, release builds can fail with:
# "IllegalArgumentException: Class NioServerSocketChannel does not have a public non-arg constructor"
-keep class io.netty.channel.socket.nio.NioServerSocketChannel { public <init>(); }
-keep class io.netty.channel.socket.nio.NioSocketChannel { public <init>(); }
-keep class io.netty.channel.epoll.EpollServerSocketChannel { public <init>(); }
-keep class io.netty.channel.epoll.EpollSocketChannel { public <init>(); }
-keep class io.netty.channel.kqueue.KQueueServerSocketChannel { public <init>(); }
-keep class io.netty.channel.kqueue.KQueueSocketChannel { public <init>(); }

# Ktor server engine classes (CIO and internals) — prevent R8 from stripping
# service-loaded or reflectively-accessed engine wiring.
-keep class io.ktor.server.engine.** { *; }
-keep class io.ktor.server.cio.** { *; }

# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.

# [NUEVO] Reglas para solucionar el error de Ktor y R8
-dontwarn java.lang.management.**
-dontwarn reactor.blockhound.**

-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Image
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.ImageWriter
-dontwarn javax.imageio.stream.ImageInputStream
-dontwarn javax.imageio.stream.ImageOutputStream
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn javax.sound.sampled.AudioFileFormat$Type
-dontwarn javax.sound.sampled.AudioFileFormat
-dontwarn javax.sound.sampled.AudioFormat$Encoding
-dontwarn javax.sound.sampled.AudioFormat
-dontwarn javax.sound.sampled.AudioInputStream
-dontwarn javax.sound.sampled.UnsupportedAudioFileException
-dontwarn javax.sound.sampled.spi.AudioFileReader
-dontwarn javax.sound.sampled.spi.FormatConversionProvider
-dontwarn javax.swing.filechooser.FileFilter

-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego

# TDLib (Telegram Database Library) rules
-keep class org.drinkless.tdlib.** { *; }
-keep interface org.drinkless.tdlib.** { *; }

# Ktor & Netty Rules (Crucial for StreamProxy)
-keep class org.slf4j.** { *; }

# Ktor Specific
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn io.netty.**

# Ensure internal server can start
-keep class com.theveloper.pixelplay.data.telegram.TelegramStreamProxy { *; }

# Keep Kotlin reflection if needed by Ktor/Serialization in Release
-keep class kotlin.reflect.** { *; }

# Kuromoji
-keep class com.atilika.kuromoji.** { *; }
-keepnames class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

# Pinyin4J
-keep class net.sourceforge.pinyin4j.** { *; }
-keepclassmembers class net.sourceforge.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**

# jsoup's Re2jRegex backend and its javax.script.ScriptEngineFactory service lookup are both
# optional/reflective paths jsoup handles gracefully at runtime when absent (this app doesn't
# pull in com.google.re2j, and Android has no javax.script). R8 can't see that the missing
# references are guarded, so without these the release build fails outright at minifyReleaseWithR8
# ("Missing class com.google.re2j.Matcher ... Compilation failed to complete").
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
# Same story for java.beans introspection — an optional reflective path some library on the
# classpath probes for and falls back gracefully when it's absent, which it always is on Android.
-dontwarn java.beans.**

# Glance Widget
-keep class * extends androidx.glance.appwidget.action.ActionCallback { <init>(); }

# =============================================================================
# MEDIAPIPE GENAI (on-device LLM inference)
# =============================================================================
# MediaPipe's generated protobuf classes reference a set of protobuf-lite internals
# (ProtoMethodMayReturnNull, ProtoNonnullApi, ProtoPresenceBits, ProtoField,
# ProtoPresenceCheckedField, ...) which are compile-time-only annotations that genuinely
# are not present at runtime. R8's static analysis flags them as missing and fails
# minifyReleaseWithR8 outright.
#
# Deliberately a wildcard rather than the exact class list the AGP-generated
# missing_rules.txt suggests: enumerating them means each release build surfaces the next
# annotation in the set and fails again, one ~12-minute round trip per class. -dontwarn only
# suppresses the missing-reference warning, it never removes code, so widening it is safe.
-dontwarn com.google.protobuf.**

# The GenAI task runner also references MediaPipe's image framework (MPImage and its
# extractors) for multimodal prompts. We depend on tasks-genai only, which does not bundle
# those classes, and OnDeviceAiClient is text-only — it calls generateResponse(String) and
# never sets vision options — so these paths are unreachable at runtime.
-dontwarn com.google.mediapipe.framework.**

# The GenAI runtime is a JNI bridge: native code resolves these classes, their
# fields and their constructors *by name*, which R8 cannot see. Without this the
# release build links fine and then fails at runtime the moment a model is loaded.
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.mediapipe.tasks.core.** { *; }
-keepclassmembers class com.google.mediapipe.** {
    native <methods>;
    <init>(...);
}

# =============================================================================
# TENSORFLOW LITE / LiteRT (TAIS on-device inference)
# =============================================================================
# Same JNI-by-name story as MediaPipe above. The AAR ships consumer rules, but TAIS
# (vocal isolation) is a feature that only exercises this path at runtime, so a
# stripped/renamed class here would surface as a release-only crash rather than a
# build failure. Cheap insurance.
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** {
    native <methods>;
    <init>(...);
}
-dontwarn org.tensorflow.lite.**

# =============================================================================
# TIMBER LOGGING OPTIMIZATION FOR RELEASE BUILDS
# =============================================================================
# Strip VERBOSE and DEBUG log calls entirely from release builds.
# This removes the method calls at bytecode level, eliminating any overhead
# from string concatenation or log message building.

-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
}

# Also strip Timber.Tree methods used by custom trees (belt and suspenders)
-assumenosideeffects class timber.log.Timber$Tree {
    public void v(...);
    public void d(...);
    public void i(...);
}

# Strip Android Log.v and Log.d calls as well
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
