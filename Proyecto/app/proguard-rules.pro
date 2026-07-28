# Rizx Player — R8/ProGuard keep rules for release (minify + resource shrink). Phase 14.
# Line numbers/attributes kept so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization ----
# Keep generated serializers and the companion .serializer() accessors for @Serializable types.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers,allowshrinking class * {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the app's serializable domain models + wire DTOs and their synthetic serializer classes.
-keep,includedescriptorclasses class fm.rizx.player.domain.model.**$$serializer { *; }
-keepclassmembers class fm.rizx.player.domain.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fm.rizx.player.data.remote.**$$serializer { *; }
-keepclassmembers class fm.rizx.player.data.remote.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Retrofit / OkHttp ----
# Retrofit service interfaces rely on generic signatures + annotations at runtime.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking interface fm.rizx.player.data.remote.**
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
# Retrofit + OkHttp ship their own consumer rules; silence known-benign warnings.
-dontwarn okhttp3.internal.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**
# OkHttp references these optional platform classes reflectively.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---- Hilt / Dagger (the Hilt Gradle plugin adds most rules; keep entry points safe) ----
-keep class dagger.hilt.** { *; }

# ---- Coroutines ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- NewPipeExtractor + Rhino (ADR 0014: native full-length YouTube audio) ----
# Rhino carries desktop-JVM integration points that simply do not exist on Android: a JSON converter
# built on java.beans introspection and a javax.script ScriptEngine factory. NewPipe uses neither — it
# only evaluates YouTube's player script — so these references are unreachable, not missing.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.engine.**
# Rhino's `optimizer` package compiles JS through the JVM's invokedynamic linker (`jdk.dynalink`), which
# Android does not ship. Rhino detects that and runs interpreted instead, so this package is never
# loaded — Android's verifier is lazy, so an unloaded class referencing an absent one is inert.
-dontwarn jdk.dynalink.**
# Rhino resolves almost everything reflectively while interpreting the player script, and NewPipe's
# extraction breaks *silently* (no audio, no error) if R8 renames something it looks up by name. This is
# the one dependency where shrinking is not worth the risk: keep it whole.
-keep class org.mozilla.javascript.** { *; }
# Same reasoning for the extractor: its services are wired through static registries and its DTOs are
# matched against JSON field names.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
# Jsoup (NewPipe's HTML parser) — its node classes are instantiated reflectively.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---- QuickJS plugin runtime (ADR 0014) ----
# JNI looks these up by name; renaming them detaches the native side from the Kotlin side.
-keep class com.dokar.quickjs.** { *; }
-keep class io.github.dokar3.** { *; }
-dontwarn com.dokar.quickjs.**

# ---- jaudiotagger (writes cover art / tags into downloaded files) ----
# Written for the desktop JVM: its artwork path reaches for java.awt and javax.imageio, and its tag
# readers are looked up reflectively per format.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.jaudiotagger.**
-keep class org.jaudiotagger.** { *; }
