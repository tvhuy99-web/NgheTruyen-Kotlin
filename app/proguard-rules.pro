-keep class vn.nghetruyen.app.playback.ReaderPlaybackService { *; }
-keepattributes *Annotation*

# java-lame ships desktop helper wrappers; Android uses only co.ntbl.lame.mp3.
-dontwarn javax.sound.sampled.**
