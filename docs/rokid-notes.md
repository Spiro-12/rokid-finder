# Notes for Rokid builders

Things this project ran into on Rokid Glasses (YodaOS, Android 12) that cost real time. If you are building your own
app for the glasses, these may save you an afternoon. Each one points at the code that handles it.

## There is no text-to-speech engine

Android's `TextToSpeech` returns status `-1` on the glasses: YodaOS ships without a system TTS engine.

**What works:** bundle your own. This app runs a Piper voice through
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (`Tts.kt`).

- Piper's phonemizer needs `espeak-ng-data` as real files on disk; it cannot read Android assets. Copy the folder
  out of assets on first run and leave a marker file so later starts skip the copy (hundreds of small files).
- Loading takes a couple of seconds. Warm it up when the app starts, and guard the start with a flag (not
  `engine != null`), or two callers racing the async load will each load the full model.
- Stream the generated audio into an `AudioTrack` from sherpa's callback so speech starts before the whole sentence
  is generated.

## The camera's reported rotation is wrong

Frames come out sideways if you trust the rotation CameraX reports. A **fixed 270°** turn is what works
(`CameraFrameSource.kt`).

## Only one microphone source works

On this audio HAL only `MediaRecorder.AudioSource.MIC` opened reliably; `VOICE_RECOGNITION` fails with `ENODEV`.
The app tries `MIC`, then `DEFAULT`, then `VOICE_RECOGNITION` (`VoiceListener.kt`), so it still runs on other
devices.

**Always release the `AudioRecord` in a `finally` block.** A leaked recorder on this HAL makes every later attempt
fail until the app's process dies.

## One touchpad tap is two key events

A tap on the temple touchpad arrives as two key codes about half a second apart. If you open a new screen on the
first one, the second one immediately closes it. Both screens ignore input for 700 ms after they appear
(`INPUT_BLOCK_MS` in `MainActivity.kt` and `RecallActivity.kt`).

Swipes arrive as D-pad / page keys, which is how the photo screen flips between memories. The volume-up key is free
for your app to use (here: record mode).

## Start from the launcher

The camera service did not start when the app was launched with `adb shell am start`; it did when opened from the
glasses' own app list. Open it on the glasses when testing.

## A service can't pop up a screen

The detection and voice work lives in a foreground service (type `camera|microphone`). On Android 12 a foreground
service cannot reliably start an activity, so the service broadcasts results to the activity that is already on
screen, and that activity opens the answer (`FinderService.broadcastAnswer`, `MainActivity.handleVoice`).

## The display is see-through

The monochrome display is optical see-through, so the camera image is never drawn, and a screen recording shows only
your overlay on black. To make a demo video, mirror camera frames onto the display behind the overlay while
recording (`FramePreview.kt`, [Recording a demo](recording-a-demo.md)).

It also means **speech is the main output**. Someone looking around for their phone is not reading the display.

## Wi-Fi turns itself off when idle

The glasses powered Wi-Fi down while idle, three times in one afternoon. If your app needs the network while it runs,
hold a `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`) for that time and release it when you stop. This app
only takes it when the experimental Mac link is built in.

## Keep the speech grammar small

Vosk with a short list of allowed phrases (plus `[unk]`) is much more accurate for tap-and-ask commands than open
dictation, and the small English model runs comfortably on the glasses (`PHONE_GRAMMAR` in `VoiceListener.kt`).

## Budget for the size

Offline speech in and out is big: about 70 MB for Vosk's model and 70 MB for the voice and its runtime. Building for
`arm64-v8a` only (`abiFilters` in `app/build.gradle.kts`) removes the x86, armv7 and mips copies some libraries ship.

## More Rokid resources

- [awesome-rokid](https://github.com/Anezium/awesome-rokid): community apps and every known way to sideload.
- [r/rokid_official](https://www.reddit.com/r/rokid_official/): the Rokid Glasses community on Reddit.
