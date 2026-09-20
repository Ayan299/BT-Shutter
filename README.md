# BT Shutter

Turns one Android phone into a Bluetooth camera-shutter remote for another.
Only the remote phone needs this app; the other phone just uses its normal camera.

## Use
1. Install the APK on the phone you'll hold as the remote.
2. Pair the two phones in Bluetooth settings.
3. Open BT Shutter. The top shows: Device is connected with '<other device name>'.
4. Open the camera on the other phone and tap SHUTTER.

## Notes
- The app sends Volume Up over Bluetooth HID (same as a selfie remote). The other phone's camera
  must have "volume key = shutter" (the default on most phones).
- Needs Android 9+ and a phone whose Bluetooth stack supports the HID-device role.
