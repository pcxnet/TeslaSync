# kotlinx.serialization — keep generated serializers for our model classes.
# (Release builds set isMinifyEnabled=false, but keep these so enabling R8
# later doesn't strip the @Serializable companions.)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class au.net.kal.teslasync.data.** {
    *** Companion;
}
-keepclasseswithmembers class au.net.kal.teslasync.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
