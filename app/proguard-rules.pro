# sherpa-onnx's JNI layer looks its Kotlin config classes up by name and field.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Called from native code by name.
-keep class app.alyo.llm.Llama { *; }
-keep interface app.alyo.llm.Llama$Sink { *; }
-keepclassmembers class * implements app.alyo.llm.Llama$Sink { *; }

# The speech library calls back into this by exact name and signature from native code.
-keepclassmembers class app.alyo.tts.VoiceEngine$* {
    public java.lang.Integer invoke(float[]);
}

# Native method looked up by name.
-keep class app.alyo.asr.ModelOptimizer { *; }
