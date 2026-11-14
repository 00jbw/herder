# Herder proguard rules.
#
# Herder has no reflection-based dependencies: JSON is the framework's
# org.json, networking is HttpURLConnection, and the app's own classes
# are reached through the Android component framework (which the
# default proguard-android-optimize.txt rules already keep). If a
# future dependency needs a keep rule, it gets added here with the
# reason named — not copied from a template wholesale.
#
# The manifest-declared components are kept explicitly: the default
# rules cover them, but naming them here costs three lines and removes
# a class of "works unsigned in CI, stripped in release" doubt.
-keep class org.bin.apk.herder.MainActivity { *; }
-keep class org.bin.apk.herder.HerderApp { *; }
-keep class org.bin.apk.herder.engine.ServerService { *; }
