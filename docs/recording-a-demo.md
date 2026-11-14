# Recording a demo

The glasses' display is see-through, so a plain screen recording shows only the overlay on black. **Record mode**
mirrors the camera onto the display behind the overlay, so the recording shows what you saw and what the glasses
showed.

## Quick way

On a Mac with the glasses connected over `adb`:

```bash
brew install scrcpy
brew install --cask android-platform-tools
```

1. Open RokidFinder on the glasses (from the app list, so the camera service starts).
2. Run:
   ```bash
   ./record-demo.sh
   ```
   It turns record mode on, starts recording, and saves `rokidfinder-demo-<date>.mp4` to your Desktop.
3. Do your demo. Press **Enter** in the terminal to stop. It turns record mode off again.

## By hand

- **Volume up** on the glasses toggles record mode (hands-free while filming).
- Or from the computer:
  ```bash
  adb shell am broadcast -a com.example.rokidfinder.TOGGLE_PREVIEW
  ```
- Record with `scrcpy --record demo.mp4`.

Record mode is off by default and costs nothing when off. While it is on, the preview is a downscaled 480-pixel-wide
copy of the camera, refreshed about 30 times a second.

## A good 30-second demo

1. Sit at a table with your phone, laptop and a cup in view.
2. Get up and walk away. "Cup remembered" and "Laptop remembered" appear.
3. Tap and ask "Where's my phone?" The photo and the spoken answer appear.
4. Swipe to show the other memories.

Remember that the recording includes the people around you.
