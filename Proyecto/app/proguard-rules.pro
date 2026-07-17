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
