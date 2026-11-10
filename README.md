# SmartPix

A small Android camera app with three things most camera apps won't let you do:

- **Lock to one lens.** Lists every camera the phone exposes, including the individual
  lenses behind a multi-camera (ultrawide, wide, tele, monochrome, close-focus).
  Tap the camera name at the top to pick one; tap ⓘ to see what your phone reports.
- **Any output size.** Choose an exact width × height in pixels. The photo is
  center-cropped to that shape (the preview shows the crop frame) and scaled to exactly that size.
- **Front camera: mirrored or true view.** Choose whether selfies are saved as the
  preview shows them (mirrored) or as others see you.

Photos are saved to `Pictures/SmartPix`.

## Download

Every push to `main` builds an APK and publishes it under
[Releases → latest](../../releases/latest).

Builds are signed with the key in `app/debug.keystore`, so each new build installs
over the previous one. It's a throwaway debug key — use a private key before
publishing anywhere.

## Notes

Some phones don't let third-party apps use every lens separately. SmartPix can only
offer what the phone exposes through Camera2; the ⓘ screen shows exactly what that is.

Requires Android 10 or later.
