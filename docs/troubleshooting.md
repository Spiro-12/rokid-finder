# Troubleshooting

To see what the app is doing, watch its log while you test:

```bash
adb logcat -s RokidFinder.Service RokidFinder.Main RokidFinder.Recall RokidFinder.Tts VoiceListener ItemDetector CameraFrameSource
```

## Nothing happens after I open it

- Check it has the camera: `adb shell dumpsys package com.example.rokidfinder | grep CAMERA` should say
  `granted=true`. If not, see [Setup step 2](setup.md#2-grant-camera-and-microphone).
- "Camera denied" on the display means the camera permission is missing.
- Open it from the glasses' app list, not with `adb shell am start` (the camera service may not start).

## It never says "… remembered"

- The item has to be clearly in view first (the detector must be at least 40% sure), then out of view for 2 full
  seconds.
- Use good light and look at the item from about an arm's length. Phones lying flat and face down are harder to spot
  than a phone at an angle.
- The same item is not saved again within 15 seconds.
- Only **phone, laptop and cup** are tracked.

## "Didn't catch that"

- Start speaking right after the tap; the microphone listens for 3 seconds.
- Use one of the phrases: "where's my phone", "where is my phone", "find my phone", or just "phone".
- Check it has the microphone: `adb shell dumpsys package com.example.rokidfinder | grep RECORD_AUDIO`.

## "One moment…"

The speech model is still loading. On the first start it is unpacked to storage, which takes longer. Try again in a
few seconds.

## It found the item but says nothing

The voice loads in the background when the app starts (and copies about 18 MB of data on the very first start). If
you ask before it is ready, the photo shows but there is no sound. Ask again.

Check the glasses' media volume too.

## "I can't see keys or wallets yet"

That is expected: the on-device model does not know keys or wallets. See
[Make it yours](make-it-yours.md#keys-wallets-and-other-things-coco-doesnt-know).

## The photo shows the wrong place

The saved photo is the last frame in which the item was clearly visible. If you carried it in your hand while walking,
that last frame may be mid-walk. It is most accurate when you set the item down and then look or walk away.

## `adb install` fails

- `INSTALL_FAILED_NO_MATCHING_ABIS`: the APK is arm64 only. It will not install on x86 emulators.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: an older copy was signed with a different key. Uninstall it first
  (`adb uninstall com.example.rokidfinder`); this deletes its memories.
