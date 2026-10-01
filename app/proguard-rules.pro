# sherpa-onnx's JNI layer looks its Kotlin config classes up by name and field.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Called from native code by name.
-keep class app.sotto.llm.Llama { *; }
-keep interface app.sotto.llm.Llama$Sink { *; }
-keepclassmembers class * implements app.sotto.llm.Llama$Sink { *; }
