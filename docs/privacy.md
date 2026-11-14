# Privacy

RokidFinder is a camera on your face that is always looking. Here is exactly what it does with what it sees and
hears.

## What stays where

| What | Where it goes | How long |
|---|---|---|
| Camera frames | Looked at in memory, about 3 a second, then thrown away. The last 3 frames of a visible item are held in memory until it is saved or the buffer is cleared. | Seconds |
| Saved photo | One JPEG per tracked item (`phone.jpg`, `laptop.jpg`, `cup.jpg`) in the app's private storage | Until the next time that item is saved, which replaces it |
| Memory row | Item, time, how sure, what it was on or next to, in a SQLite file in private storage | Same as the photo |
| Microphone audio | Recognized on the glasses, then thrown away. Only the recognized words are used. | 3 seconds, only after a tap |

- **No network** in the default build. No account, no cloud, no analytics, no crash reporting.
- **No backups.** `allowBackup` is off, so Android does not copy the memories anywhere.
- **No face recognition.** The detector only knows object types like "person", "cup" and "laptop", and the app
  ignores people completely.
- Other apps cannot read the photos (Android app-private storage).

## When the camera and microphone are on

- The **camera** starts when you open the app and Android shows an ongoing notification, "RokidFinder watching".
  It **keeps watching in the background after you leave the app**, because that is the point: it has to see you put
  things down. To stop it, force-stop the app (Settings, or `adb shell am force-stop com.example.rokidfinder`).
  Restarting the glasses also stops it until you open the app again.
- The **microphone** only opens for 3 seconds after you tap the touchpad.

## Erasing everything

Uninstall the app, or clear its data:

```bash
adb shell pm clear com.example.rokidfinder
```

## People around you

The saved photos are whatever the camera saw when you put something down, so they can include other people.
Nothing identifies them and nothing leaves the glasses, but please be considerate: tell people when you are
wearing a recording device, and follow the rules of the places you go.

## The experimental Mac link

The code also contains an experimental link to a helper program on a Mac (`LinkClient.kt` and friends). It is
**switched off in the default build and in the released APK.** With it switched off, the link code never runs.

If you build with `-PbrainLink=true`, the glasses look for a service called `_syntropic-brain._tcp` on the local
network and, if they find one, stream camera frames and your voice to it over an unencrypted connection. **There is
no pairing**: any device on the same network that advertises that name would receive them. Only turn it on for your
own experiments, on a network you control.

The app still lists the `INTERNET` and Wi-Fi permissions because that code is compiled in. In the default build
nothing uses them.
