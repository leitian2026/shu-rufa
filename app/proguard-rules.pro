# R8 已启用(isMinifyEnabled = true)。
# 清单里声明的 Service/Activity 会被自动保留,
# 词库放在 assets,不受混淆影响,没有反射,一般不需要额外规则。

# OkHttp / Okio(4.x 自带 consumer 规则,这里只屏蔽可选依赖的告警)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
