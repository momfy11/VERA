# Filament / SceneView 3D rendering — JNI classes must not be stripped
-keep class com.google.android.filament.** { *; }
-keep class io.github.sceneview.** { *; }
-dontwarn com.google.android.filament.**
-dontwarn io.github.sceneview.**

-keep class com.vera.android.data.api.** { *; }
-keep class com.vera.android.data.ws.** { *; }
-keepattributes *Annotation*
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
