# Test APK only: literal JNI export in the shared instrumentation probe.
# No change to the production release's keep rules or shrinking.
-keep class com.tyranor.next.releaseprobe.EthornellReleaseInstrumentation { *; }
