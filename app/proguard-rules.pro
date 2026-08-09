-keep class vn.nghetruyen.app.playback.ReaderPlaybackService { *; }
-keepattributes *Annotation*

# java-lame ships desktop helper wrappers; Android uses only co.ntbl.lame.mp3.
-dontwarn javax.sound.sampled.**

# Rhino's optional JavaBeans JSON converter and LuaJ's javax.script adapter target
# desktop Java APIs that Android does not ship. NgheTruyen uses the Android-native
# Rhino/Lua runtimes directly, so these optional integration classes are unreachable.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.ScriptEngineFactory
