# Wayneedle ProGuard/R8 rules.
# Room, Compose, DataStore and coroutines ship their own consumer rules.
# Keep stack-trace line numbers readable for bug reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
