# tv-home

A personal home screen for a Google TV: clock, date, Riyadh prayer times (Umm al-Qura method),
weather, upcoming matches and app shortcuts, over slowly changing scenery.

- `app/assets/index.html` is the whole screen (one HTML page).
- `app/src` is a small Android wrapper that shows the page full screen, opens TV apps for it,
  and (optionally) sends the remote's Home button to it.
- `build.sh` builds the APK with the Android SDK command-line tools; GitHub Actions runs it on
  every push to `main` and publishes `tv-home.apk` as the latest release.

`app/debug.keystore` is a throwaway key for sideloading on one's own TV. It is not a secret and
must not be used to publish the app anywhere.

## Changing the design

Edit `app/assets/index.html` and push to `main`. The app on the TV checks this file in the
repository (at most every ten minutes, when the home screen comes to the front) and reloads itself
when it has changed, so design changes do not need a reinstall. Changes to the Android wrapper do:
install the new `tv-home.apk` from the latest release.
