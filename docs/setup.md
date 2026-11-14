# Setup

From nothing to "Where's my phone?" on your Rokid Glasses.

## What you need

- **Rokid Glasses** with the camera and display (YodaOS, Android 12, arm64).
- A computer with **`adb`** (Android platform tools):
  - Mac: `brew install --cask android-platform-tools`
  - Windows / Linux: [download platform tools](https://developer.android.com/tools/releases/platform-tools)
- A way for `adb` to reach the glasses. Most Rokid developers use the USB developer cable. For cable-free
  options (Wi-Fi, Bluetooth, WebUSB installers), see [awesome-rokid](https://github.com/Anezium/awesome-rokid).

Check the glasses are connected:

```bash
adb devices -l
```

You should see one device listed as `device` (not `unauthorized`).

## 1. Install

Download `rokid-finder.apk` from the [latest release](https://github.com/Spiro-12/rokid-finder/releases/latest), then:

```bash
adb install -r rokid-finder.apk
```

`-r` replaces an older version and keeps its memories.

## 2. Grant camera and microphone

```bash
adb shell pm grant com.example.rokidfinder android.permission.CAMERA
adb shell pm grant com.example.rokidfinder android.permission.RECORD_AUDIO
```

The app also asks for these itself on first start, but answering a permission dialog on the glasses is awkward.
Granting over `adb` always works.

## 3. Start it on the glasses

Open **RokidFinder** from the glasses' own app list.

Start it there, not with `adb shell am start`: when launched over `adb`, the camera service did not start in our
testing (see [Notes for Rokid builders](rokid-notes.md#start-from-the-launcher)).

On the first start the app copies its voice data and unpacks the speech model. That takes a little while. If you tap
during that time it says "One moment…". After that, the display shows a single dim dot: it is watching.

## 4. First test

1. Put your phone on a table in good light.
2. Look at it for a second or two from about an arm's length.
3. Look away (or walk away) for at least 2 seconds. You should see **"Phone remembered"** fade in and out.
4. Tap the temple touchpad once and say **"Where's my phone?"** within 3 seconds.
5. You should see the photo full screen and hear *"Your phone, on the …, just now."*

Phrases it understands: "where is my phone", "where's my phone", "find my phone", "my phone", "phone". The same for
**laptop** and **cup**. See [Troubleshooting](troubleshooting.md) if nothing happens.

## Updating

Install the new APK with `adb install -r`. Memories and permissions are kept.

## Uninstalling

```bash
adb uninstall com.example.rokidfinder
```

This deletes every saved photo and memory.

## Build from source

You need **JDK 17** and the **Android SDK** (platform 34). Android Studio includes both; on the command line:

```bash
git clone https://github.com/Spiro-12/rokid-finder.git
cd rokid-finder
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If Gradle cannot find the SDK, create `local.properties` with `sdk.dir=/path/to/Android/sdk`, or set `ANDROID_HOME`.

The clone is large (about 85 MB) because the offline models live in `app/src/main/assets/` (see
[How it works](how-it-works.md#whats-in-the-apk)).

### Build options

| Option | What it does |
|---|---|
| `-PbrainLink=true` | Turns on the experimental link to a Mac helper on the same Wi-Fi. **Off by default and not supported here.** It has no pairing, so only use it on a network you trust. See [Privacy](privacy.md#the-experimental-mac-link). |
