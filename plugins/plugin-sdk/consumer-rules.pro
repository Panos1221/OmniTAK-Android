# Public SDK surface — keep the OmniTAKPlugin interface and the value types
# the host adapts to/from. R8 in the :app release build must not rename or
# strip these (plugins are discovered via the registry by interface).
-keep interface soy.engindearing.omnitak.plugin.** { *; }
-keep class soy.engindearing.omnitak.plugin.** { *; }
