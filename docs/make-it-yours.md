# Make it yours

## Track different objects

Any of the [COCO object types](https://github.com/amikelive/coco-labels/blob/master/coco-labels-2014_2017.txt)
works without training anything, for example `backpack`, `handbag`, `umbrella`, `remote`, `book`, `bottle`,
`scissors`, `teddy bear`.

Three places to change:

1. **`TrackedItems.kt`**: add a line to `ALL`.
   ```kotlin
   Spec(key = "remote", coco = "remote", display = "remote", kind = KIND_EVERYDAY),
   ```
   `coco` must match the model's label exactly. `display` is the word people say and see.
2. **`VoiceListener.kt`**: add the phrases to `PHONE_GRAMMAR`, the same way as the others
   (`"where is my remote","where's my remote",…,"remote",`). Vosk can only hear words in this list, and every word
   must be in its English vocabulary.
3. **`ItemDetector.kt`**: if the new item was in `NEIGHBOR_LABELS` (like `book` or `backpack`), remove it there so it
   is not used as both an item and a "next to" anchor.

Keep the list short (3 to 5 items). Each item adds a watcher, a small frame buffer and more phrases to recognize.

## Change the timings

All in `FinderService.kt` and `ItemDetector.kt`:

| Setting | Default | Turn it… |
|---|---|---|
| `TARGET_FPS` | 3 | Up for faster reactions, down for battery and heat |
| `ABSENT_THRESHOLD_MS` | 2000 | Up if it saves too eagerly when you just glance away |
| `SAVE_COOLDOWN_MS` | 15000 | Down if you move things often |
| `SCORE_THRESHOLD` | 0.40 | Down to catch more, up for fewer wrong saves |
| `LISTEN_MS` | 3000 | Up for people who speak slowly |
| `SPOKEN_THRESHOLD` (`SceneContext.kt`) | 0.45 | Up if the "next to…" part is often wrong |

## Change the words

`MemoryPhrases.kt` builds every sentence and caption. Change the wording there and both the screen and the voice
follow.

## Keys, wallets and other things COCO doesn't know

This is the most useful thing someone could add. The idea:

1. Train a small detector for the new object, for example a YOLO model on a public "keys" dataset plus a few hundred
   photos taken from the glasses, and export it to TFLite.
2. Load it in a second detector next to EfficientDet and merge its detections into the same list `ItemDetector`
   returns.
3. Add the item to `TrackedItems` with the new model's label. Keys and wallet phrases are already in the speech
   grammar.
4. Remove that word from `NEEDS_BRAIN` in `FinderService.kt`, so asking for it opens the photo instead of the
   "I can't see keys or wallets yet" message.

If you try it, please open an issue or a pull request, even if it only half works.

## Another voice or language

The voice is any [Piper](https://huggingface.co/rhasspy/piper-voices) model in sherpa-onnx format: replace the
`vits-piper-…` folder in `app/src/main/assets/` and the names at the top of `Tts.kt`.

For another language you also need a [Vosk model](https://alphacephei.com/vosk/models) for it in
`assets/model-en-us/` (or change `MODEL_ASSET_DIR`), the grammar in that language, and the sentences in
`MemoryPhrases.kt`.
