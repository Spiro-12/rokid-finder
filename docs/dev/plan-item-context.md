# Improve item context: from "Phone, 5 minutes ago" to a real answer

**Goal.** When the user asks "where's my phone?", the spoken answer must carry the
context a photo carries — usable eyes-free:

> "Your phone — on the dining table, next to your laptop, in a coffee shop —
> five minutes ago."

The photo stays (hero mode unchanged); the voice line gets the upgrade.

**Constraints that shape everything** (from the codebase + device):

- Glasses run **YodaOS** — bare Android, no Google Play Services (confirmed
  indirectly: no system TTS, see `Tts.kt` header comment). No fused location.
- **Wi-Fi only, no cellular** → assume *no internet when out walking*, which is
  exactly when place context matters. Everything must work offline.
- Almost certainly **no GNSS chip** → geolocation + reverse-geocoding ("Corgi
  cafe" via coordinates) is **out of scope** for this device. Verify once when
  glasses are plugged in: `adb shell pm list features | grep -i location`.
- Future-proofing = *sensor-agnostic design*: memories carry a `place` string;
  today it comes from a vision classifier, tomorrow it could come from GPS+POI
  lookup on a phone-class device. The schema and phrase-builder don't care.

**Feasibility verdict on the three ideas discussed:**

| Idea | Verdict | Why |
|---|---|---|
| GPS → named place ("Corgi cafe") | ❌ park | No GNSS, no GMS, no internet on the go. Revisit on future hardware; schema is ready for it. |
| Richer caption from the image | ✅ Phase A | EfficientDet already sees all 90 COCO classes per frame; we currently discard everything but 3 labels. Surface + neighbor objects are free. |
| Place timeline (home → street → cafe) | ✅ Phase B | Places365-style scene classifier, ~4–14 MB TFLite, low-cadence, fully offline. This is the "Phase 3" the `gist` column was reserved for. |
| Named places without GPS | ❌ dropped | Wi-Fi SSID fingerprinting + one-time voice naming — descoped, not being pursued. |
| VLM caption at save-time | 💭 future possibility | Moondream/SmolVLM ~300 MB+, seconds per caption on the AR1. Only worth revisiting if template captions prove insufficient. |

---

## Phase A — Scene context from what the detector already sees (no new models)

**Status: ✅ Implemented** (`e39c72c`, branch `feat/scene-context`). Notes below
record where the landed code refined or deviated from the plan as written.

The one-frame insight: `ItemDetector.detect()` runs EfficientDet with
`setMaxResults(15)` and gets label + score + **bounding box** for every COCO
object in frame — dining table, chair, couch, bed, tv, keyboard, the *other*
tracked items — then throws away all but 3 confidence scores.

### A1. Widen `ItemDetector` output — done

- Returns full detections: `data class Det(label: String, score: Float, box: RectF)`.
- `bestByTrackedLabel()` keeps the old per-tracked-label max-confidence map as
  a helper so `FinderService`'s visible/absent logic is untouched.
- Context allowlist landed as two sets rather than one flat list:
  `SceneContext.SURFACE_LABELS` (`dining table, couch, bed, chair, sink`) and
  `ItemDetector.NEIGHBOR_LABELS` (`tv, keyboard, mouse, book, bottle,
  refrigerator, backpack, handbag`). `ItemDetector.CONTEXT_LABELS` is the union,
  so a surface added in `SceneContext` can't be silently filtered out at the
  detector layer. Context threshold is `CONTEXT_THRESHOLD = 0.35f` (item
  visibility unchanged at `SCORE_THRESHOLD = 0.40f`), as planned.

### A2. Capture context at the moment of the last good sighting — done

- `FinderService.onVisible` now buffers a `Sighting(frame, dets)` per good
  look instead of a bare `Bitmap`, so each buffered frame carries its own
  detection list.
- `onAbsent` derives context from the *same frame* as the saved snapshot via
  the new `SceneContext.derive()`:
  - **surface**: `isOn()` — item's horizontal center falls inside the
    surface's horizontal span, item's bottom edge within the surface's
    vertical span (30%-of-item-height margin above the surface top, since a
    cup on a table often crops the table box just below the rim).
  - **near**: `centerDistance()` (Chebyshev distance) within `NEAR_FACTOR =
    1.5` item-widths, tracked items sorted ahead of generic objects, capped
    at `MAX_NEAR = 2` — matches plan exactly.
  - New refinement not in the original plan: only detections scoring above
    `SceneContext.SPOKEN_THRESHOLD = 0.45f` are eligible as context at all —
    stricter than the 0.35 model floor, because a wrong "next to the book"
    costs more trust than a missing one.
- All heuristic, all explainable, no ML beyond what already runs.

### A3. Schema v2 in `MemoryStore` — done

```sql
CREATE TABLE memories(
  item       TEXT PRIMARY KEY,
  kind       TEXT NOT NULL,
  place      TEXT,          -- Phase B fills this ("coffee shop", "home")
  surface    TEXT,          -- "dining table" | NULL
  near       TEXT,          -- JSON array: ["laptop","keyboard"] | NULL
  snapshot   TEXT,
  ts         INTEGER NOT NULL,
  confidence REAL NOT NULL
)
```

*(Implementation note: the originally-planned `gist` column was dropped in
review — prose is always composed at read time by MemoryPhrases from the
structured fields, so persisted wording can never go stale against them.)*

- Bump `DB_VERSION` to 2. Current `onUpgrade` drops and recreates — acceptable
  (memories are ephemeral by design; only latest sighting is kept anyway).
- `save()` takes the new optional fields; `Memory` data class grows to match.

### A4. Phrase builder (single source of truth) — done

- New `MemoryPhrases.kt`: `fun spoken(m: Memory): String` and
  `fun caption(m: Memory): String`, composing from structured fields:
  - `"Your phone"` + `", on the dining table"?` + `", next to your laptop"?`
    + `", in a coffee shop"?` (Phase B) + `", five minutes ago"`.
  - Number words for TTS ("five minutes ago" reads better than "5").
  - Graceful degradation: with no context fields it must still say exactly
    what it says today.
  - Shared `Ago` bucket ladder (`JustNow`/`Minutes`/`Hours`/`Yesterday`/`Days`)
    feeds both `agoDisplay()` (screen, digits) and `agoSpoken()` (TTS, number
    words) so the caption and the voice line always bucket a timestamp the
    same way — this consolidation replaced `RecallActivity.humanAgo()`, which
    is now deleted.
- `RecallActivity.speakHero()` and the hero/browse captions switch to it.
- Unplanned follow-on fix that landed in the same commit: the hero scrim
  (`activity_recall.xml`) was a fixed 120dp `View` that could be overflowed by
  a multi-line context caption. It's now the caption `LinearLayout`'s own
  background, so it always sizes to the text.

**Acceptance (Phase A):** leave phone next to laptop on a table, walk away,
ask "phone" → hears "Your phone, on the dining table, next to your laptop,
two minutes ago." Item alone on the floor → degrades to today's phrase.
✅ Verified against the landed code path (`SceneContext` → `MemoryStore.save`
→ `MemoryPhrases.spoken`); not yet verified on-glasses (see open items below).

---

## Phase B — Place awareness: classifier + timeline

### B1. Source a scene-classification model

- Target: MobileNetV2/V3 trained on **Places365** (365 scene classes incl.
  coffee_shop, supermarket, street, home_office, living_room, kitchen…),
  converted to TFLite, int8, ≤15 MB. Task includes finding a ready conversion
  or converting from the official Places365 weights.
- Collapse 365 classes → ~10 coarse labels we can speak with confidence:
  `home, office, coffee shop, restaurant, supermarket, shop, street, park,
  car, transit`. Mapping table in code; unknown/low-confidence → `null`
  (say nothing rather than something wrong).
- **Risk / fallback:** if no good TFLite conversion exists, fall back to a
  CLIP-style tiny image encoder + text-prompt classification via
  sherpa-onnx's ONNX runtime (already bundled), or defer B and ship A.

### B2. `PlaceTracker` — smoothed current-place state

- New class fed from the same camera pipeline in `FinderService`, but at
  **low cadence** (~1 frame every 2–3 s, interleaved so we never run both
  models on the same frame budget; detector stays at 3 fps).
- Majority vote over a sliding window (e.g. best-of-last-7) + hysteresis:
  only switch current place after N consistent votes. Prevents "street →
  supermarket → street" flapping at the shop entrance.
- Exposes `currentPlace: String?` and emits transitions.

### B3. Place timeline (the user's home → street → cafe journey)

- New table: `place_log(id INTEGER PRIMARY KEY, place TEXT, enter_ts INTEGER,
  exit_ts INTEGER)`, ring-capped (e.g. last 200 rows).
- On each `PlaceTracker` transition: close previous row, open new one.
- This enables the *"the coffee shop you entered"* framing: at recall time, if
  the memory's place differs from `currentPlace`, phrase it as past-tense
  context — "in the coffee shop you were at" — and it's the foundation for
  future queries like "where was I an hour ago?".

### B4. Stamp memories with place

- `FinderService.onAbsent` → `store.save(..., place = placeTracker.currentPlace)`.
- Phrase builder appends `", in a coffee shop"` / `", at home"`.

**Acceptance (Phase B):** walk from home to a cafe wearing the glasses, set
the cup down, look away, leave; ask "cup" → "Your cup, on the dining table,
in a coffee shop, ten minutes ago." `place_log` shows home → street → coffee
shop with sane timestamps.

---

**Future possibility (not planned) — VLM captions.** If template captions
from Phase A+B ever prove insufficient, a tiny VLM (SmolVLM-500M /
Moondream-0.5B, int8 ONNX via the bundled onnxruntime) could run once per
save, async, on the snapshot — not per frame — composing a freeform caption
alongside the structured fields. Costs: +300 MB–1 GB assets, battery, real
engineering. Not scoped or scheduled; revisit only if a real gap shows up
after living with Phase B.

---

## Build order & file map

| Step | Files | Status |
|---|---|---|
| A1 detector boxes | `ItemDetector.kt` | ✅ done |
| A2 context capture | `FinderService.kt`, new `SceneContext.kt` | ✅ done |
| A3 schema v2 | `MemoryStore.kt` | ✅ done |
| A4 phrase builder | new `MemoryPhrases.kt`, `RecallActivity.kt`, `activity_recall.xml` | ✅ done |
| B1 model | `app/src/main/assets/`, model-sourcing task | not started |
| B2 tracker | new `PlaceTracker.kt`, `FinderService.kt` | not started |
| B3 timeline | `MemoryStore.kt` | not started |
| B4 stamp + speak | `FinderService.kt`, `MemoryPhrases.kt` | not started |

Each phase lands on its own branch and is demoable on-glasses before the next
starts (matches the repo's existing phase-branch workflow).

## Open items to verify on hardware (first time glasses are plugged in)

1. `adb shell pm list features | grep -iE "location|gps|wifi"` — settle the
   GNSS question for the record.
2. Headroom check: detector at 3 fps + place model at 0.3–0.5 fps — watch
   thermals/battery over a 30-min wear.
3. EfficientDet-Lite0's real-world recall on `dining table`/`couch` from the
   glasses' wide-angle lens (boxes may be partial at close range — tune the
   "on" heuristic with real footage via `record-demo.sh`).
