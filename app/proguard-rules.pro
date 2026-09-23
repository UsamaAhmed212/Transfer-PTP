# Ktor & KotlinX Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class *$$serializer {
    *** INSTANCE;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializer *** companion(...);
}

# Netty & Ktor warnings suppression
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn javax.lang.model.**
