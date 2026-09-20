# libxposed module packaging rules: keep the entry class reachable by the
# framework and rewrite the entry list when R8 renames it.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class org.a4real.skopos.runtime.XposedEntry extends io.github.libxposed.api.XposedModule {
    public <init>();
}