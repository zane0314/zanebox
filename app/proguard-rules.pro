# go.** and libcore.** are already retained by the AAR's consumer rules.
# Native callbacks call these public methods through Go/Seq.
-keep class com.zane.zanebox.core.NativePlatform { public *; }
-keep class com.zane.zanebox.core.StringValues { public *; }
