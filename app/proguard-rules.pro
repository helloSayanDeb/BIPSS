# BIPSS release shrinking rules.
#
# The release build type has always referenced this file, but it did not
# exist, so minified builds ran with no app-specific keeps at all.

# WorkManager persists worker class names in its own database and re-creates
# them by name after an update; renaming a worker would orphan scheduled work.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# kotlinx.serialization: keep generated serializers for our @Serializable
# classes (env cache, Drive backup payload, model manifest).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.bipss.app.** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.bipss.app.**$$serializer { *; }

# MediaPipe GenAI loads native code and reflects on its own classes.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Room entities/DAOs are handled by Room's consumer rules; the app's own
# query-result data classes are plain constructors and need nothing extra.

# Firestore is written with Map payloads only (no reflective POJO mapping).

# MediaPipe GenAI ships AutoValue/JavaPoet (compile-time annotation
# processing) inside its runtime jar; their javax.lang.model references are
# never executed on Android.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
