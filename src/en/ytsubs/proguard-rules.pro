# NewPipe Extractor includes the Rhino JavaScript engine, which mentions
# desktop-only classes that do not exist on Android. Safe to ignore.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Keep NewPipe Extractor and Rhino working after the app is shrunk.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
