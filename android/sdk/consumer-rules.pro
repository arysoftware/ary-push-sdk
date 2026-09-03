# ARY Push SDK consumer ProGuard/R8 rules.
# These are applied automatically to any application that depends on the SDK.

# The public facade and configuration types are referenced by host code and, in the Flutter
# plugin, by name. Keep their public members.
-keep public class com.ary.push.ARYPush { public *; }
-keep public class com.ary.push.ARYPushConfig { public *; }
-keep public class com.ary.push.PushBackendConfig { public *; }
-keep public class com.ary.push.NetworkConfig { public *; }
-keep public class com.ary.push.RetryConfig { public *; }
-keep public class com.ary.push.model.** { public *; }
-keep public interface com.ary.push.backend.PushBackend { *; }
-keep public interface com.ary.push.api.** { *; }

# Components resolved by the framework from the merged manifest.
-keep class com.ary.push.messaging.ARYPushFirebaseMessagingService { *; }
-keep class com.ary.push.internal.NotificationOpenActivity { *; }
-keep class com.ary.push.startup.ARYPushInitializer { *; }

# OkHttp ships its own rules, but these silence the well-known optional-dependency warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
