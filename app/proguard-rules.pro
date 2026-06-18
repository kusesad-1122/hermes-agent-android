# Hermes Agent Android — ProGuard Rules
# Keep Python/Chaquopy bridge classes
-keep class com.chaquo.python.** { *; }
-keep class com.hermes.agent.HermesApp { *; }
-keep class com.hermes.agent.service.AgentService { *; }

# Keep Compose
-dontwarn androidx.compose.**

# Keep Kotlin coroutines
-dontwarn kotlinx.coroutines.**