# Contributing

Thanks for helping. RokidFinder is small on purpose, so changes are easy to review.

## Good first contributions

- **Keys and wallets.** A small custom detector loaded next to EfficientDet. This is the most wanted change; see
  [Make it yours](docs/make-it-yours.md#keys-wallets-and-other-things-coco-doesnt-know).
- **Field reports.** Try it on your glasses and [tell us how it went](https://github.com/Spiro-12/rokid-finder/issues/new/choose).
- **More Rokid quirks** you have hit, for [Notes for Rokid builders](docs/rokid-notes.md).
- **Other languages**: a Vosk model, a Piper voice and translated sentences.

## Build

JDK 17 and the Android SDK (platform 34):

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI builds every pull request (`.github/workflows/build.yml`), both the default build and the `-PbrainLink=true`
build.

## Test on real glasses

There are no automated tests that can stand in for the glasses, so please say in your pull request what you tried on
a device:

1. Open the app from the launcher; the dot appears.
2. Put your phone down, look away for 2 s: "Phone remembered".
3. Tap, "Where's my phone?": photo and spoken answer.
4. Tap, "Find my keys": the "I can't see keys or wallets yet" line.
5. Volume up: record mode on and off.

## Pull requests

- One change per pull request, with a sentence on why.
- Keep everything on the glasses. Changes that send data off the device need to be off by default and explained in
  [Privacy](docs/privacy.md).
- Update the docs when behaviour changes.

## Code of conduct

Be kind and assume good intent.
