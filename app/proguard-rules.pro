# OMNIX ProGuard Rules

# Keep Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep @kotlinx.serialization.Serializable class com.omnix.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer();
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep LiteRT / TensorFlow Lite
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# Keep Porcupine
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

# Keep AccessibilityService
-keep class com.omnix.agent.core.OmnixAccessibilityService { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Keep WorkManager workers
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Keep BroadcastReceivers
-keep class com.omnix.agent.core.OmnixBootReceiver { *; }
-keep class com.omnix.agent.discovery.NewAppReceiver { *; }

# Keep all OMNIX model/data classes
-keep class com.omnix.agent.database.** { *; }
-keep class com.omnix.agent.skills.** { *; }
