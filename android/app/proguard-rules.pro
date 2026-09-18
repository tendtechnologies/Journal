# Release shrinker rules for Journal.
#
# Firebase, Health Connect and the credentials providers ship their own
# consumer ProGuard rules via their AARs; nothing in this app's own code
# relies on reflection, so the defaults are enough. This file exists so
# the release buildType's proguardFiles() reference resolves.
