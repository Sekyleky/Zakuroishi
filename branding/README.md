# Zakuroishi branding

`zakuroishi.svg` is the user-supplied logo. The Android `brand_logo.xml` vector
preserves the corrected logo's 22 colored paths, black canvas, CSS fill rules
and 30480×30480 view box. SVG metadata is not
executed or included in Android resources.

The launcher uses an adaptive icon with the artwork inset by one sixth on each
edge. Its foreground omits only the SVG's black canvas, using a matching black
adaptive background under circular and rounded masks. The
library header uses the full original logo. Both Windows and container APK builds
compile these standard Android resources.

The visible app name is Zakuroishi. Package `io.github.zakuro.android`, JNI names,
deep links, frontend reference identifiers, backup formats and existing export
folder names stay stable so upgrades retain installed games and saves. Credits
to the upstream Zakuro project retain its original name.
