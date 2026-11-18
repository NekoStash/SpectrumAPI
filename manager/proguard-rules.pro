# Roxy's consumer proguard.txt and KSP META-INF/proguard/roxy-entry.pro keep
# the literal META-INF/xposed/java_init.list entry and its constructor.
# Do not keep the entire app, SDK, Compose tree or Roxy tree.
# Android's optimize defaults already preserve Parcelable.CREATOR. AIDL uses
# direct Binder references; app reflection targets external SystemUI/framework
# classes, not Spectrum classes. No additional app-wide keep/dontwarn is needed.
