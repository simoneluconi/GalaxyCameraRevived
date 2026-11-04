# Custom views are inflated from XML by class name
-keep class com.simoneluconi.gccamera.ui.widget.** { <init>(...); }

# JNI entry points are looked up by name
-keep class com.simoneluconi.gccamera.raw.NativeDng { native <methods>; }
