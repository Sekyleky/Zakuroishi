# Zakuro Android 0.1.6 — hotfix

Fixes **"Cannot open saves. Unsafe directory"** in 0.1.5, which stopped games from
starting and blocked the Saves button. Android reaches app storage through a
system folder link, and the 0.1.5 safety check wrongly rejected it. Shortcuts
inside Zakuro's own save folders are still rejected.

Install **Zakuro-Android-0.1.6.apk** over 0.1.5 or 0.1.4. Your games and saves are
kept. See [0.1.5](https://github.com/Sekyleky/zakuro-android/releases/tag/android-v0.1.5)
for how save export and restore work.
