# The stub component pool is referenced only from the manifest and by name from the engine.
-keep class com.atlant.space.engine.runtime.stub.** { *; }

# AIDL binder served to guest processes.
-keep class com.atlant.space.engine.ipc.** { *; }

# The engine reaches framework internals by reflection; keep its runtime layer intact so names
# used in getDeclaredField/Method lookups are not renamed away.
-keep class com.atlant.space.engine.runtime.** { *; }

# Application and the hidden-API bypass helper (loaded by reflection).
-keep class com.atlant.space.app.AtlantApp { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }

# Kotlin metadata the reflective code relies on.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-dontwarn org.lsposed.hiddenapibypass.**
