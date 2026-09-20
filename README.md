# BT Shutter

Turns one Android phone into a Bluetooth camera-shutter remote for another.
Only the remote phone needs this app; the other phone just uses its normal camera.

## Get the signed APK
**Android Studio:** open this folder, wait for Gradle sync, then in a terminal run
`gradle assembleRelease` (or Build > Select Build Variant > release, then Build > Build APK(s)).
Output: `app/build/outputs/apk/release/app-release.apk`

**No local setup:** push this folder to a GitHub repo. The included workflow builds the APK
(Actions tab > Build APK > artifact `bt-shutter-apk`).

Signing uses `app/shutter-release.jks` (password `btshutter`). Replace it with your own key before publishing.

## Use
1. Install the APK on the phone you'll hold as the remote.
2. Pair the two phones in Bluetooth settings.
3. Open BT Shutter. The top shows: Device is connected with '<other device name>'.
4. Open the camera on the other phone and tap SHUTTER.

## Notes
- The app sends Volume Up over Bluetooth HID (same as a selfie remote). The other phone's camera
  must have "volume key = shutter" (the default on most phones).
- Needs Android 9+ and a phone whose Bluetooth stack supports the HID-device role.
