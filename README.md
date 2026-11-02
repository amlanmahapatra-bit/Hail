# Hail

One request, every cab, best price. An AI agent that fetches fares from Uber, Ola and Rapido
on your phone and lists every option best-first. You tap the one you want, Hail opens that
app with your trip filled in, and you book it there.

Status: Week 1. The backend agent loop and the confirmation gate work end to end with a fake
phone. The Android app builds: consent screen, connected apps, accessibility service, screen
dumper, Stop notification, chat with an option picker, and draft fare readers for Uber, Ola and
Rapido written from the founder's screen recordings (docs/readers.md). The readers still need
checking on a real Android phone.

## Backend: setup

Needs [uv](https://docs.astral.sh/uv/) (`curl -LsSf https://astral.sh/uv/install.sh | sh`).

```bash
cd backend
uv sync                      # creates .venv and installs everything
cp .env.example .env         # then put your OpenRouter key in OPENROUTER_API_KEY
```

## Run the checks

```bash
cd backend
uv run ruff check . && uv run ruff format --check .
uv run pytest -q             # expect: "60 passed"
```

## Acceptance test: fake phone

```bash
cd backend
uv run python ../tools/fake_phone.py                    # offline rule-based agent, no key needed
uv run python ../tools/fake_phone.py --llm openrouter   # real Gemini via OpenRouter
uv run python ../tools/fake_phone.py --category auto     # answer "Which ride?" with auto
```
Expected final lines:
```
FINAL: Opened Uber with your trip. Pick Premier AC and tap Book there. You save ₹67.
PASS: 3 fare checks -> options card -> forged tap refused -> user's pick opened
```
The fares are real prices transcribed from the founder's recordings (Bangalore, 2 Nov 2026),
in `tools/recorded_fares_blr_2026-11-02.json`.

## Run the server

```bash
cd backend
uv run uvicorn app.main:app_factory --factory --reload --port 8000
# API docs: http://127.0.0.1:8000/docs
uv run python ../tools/fake_phone.py --base-url http://127.0.0.1:8000
```

To reach it from a phone, use a Cloudflare quick tunnel:
`cloudflared tunnel --url http://localhost:8000` (it prints an `https://….trycloudflare.com` URL).

## Android: get the APK

**No Android Studio needed.** Every push builds the APK on GitHub:
1. GitHub → repo → **Actions** → the latest **CI** run → **Artifacts** → `hail-debug-apk`
   (a zip containing `app-debug.apk`).
2. Copy `app-debug.apk` to the phone and open it. Allow "Install unknown apps" for your
   browser or file manager when asked.

To build locally instead (needs JDK 21 and the Android SDK): `cd android && ./gradlew assembleDebug`.
Fare-reader unit tests: `cd android && ./gradlew testDebugUnitTest` (fixtures from the recordings).
The APK lands in `android/app/build/outputs/apk/debug/`.

### First run on the phone
1. Open Hail → read the consent screen → **I agree** → Android Accessibility settings →
   **Hail ride comparison** → turn on.
   - If it's greyed out or says "Restricted setting" (Android 13+ sideloaded apps): App info →
     ⋮ (top right) → **Allow restricted settings**, then turn it on again.
2. **Connected apps** opens next. For each of Uber, Ola and Rapido: install it if missing, tap
   **Open … to log in**, and log in inside that app once. Leave **Use … with Hail** ticked.
3. Allow notifications (needed for the Stop button).
4. **Debug** → set **Backend URL** to your tunnel URL (see "Run the server"), then **Save URL**.

### Test fare reading on the phone (5 minutes, no server needed)
1. Debug → **Test fare check** → type a destination near you (e.g. "Forum Mall Koramangala").
2. Tap **Uber**. Hail opens Uber, taps "Where to?", types the destination, picks the matching
   suggestion and reads the fare list. You'll see the screens change; don't touch the phone.
   Tap **Stop** in the notification at any time.
3. Back in Hail's Debug screen you see what it read ("Uber Go AC: ₹118.75 · 6 min") and every step
   it took. Repeat for **Ola** and **Rapido**.
4. If an app fails, send me the "Steps" text and a dump of the screen where it stopped (next
   section). Hail never books: it never taps Book, Confirm, Request, Choose, Pay or Cancel.

### Collect screen dumps from Uber, Ola and Rapido (when a test fails)
1. Debug → **Start dump mode**. A "Hail dump mode" notification appears.
2. Open the ride app and enter a trip (e.g. Powai → Andheri station) until the fare list shows.
3. Pull down notifications → **Dump screen**. The notification says
   `Saved Downloads/Hail/hail-dump-<app>-….json`.
4. In **each** of Rapido, Uber and Ola, dump these screens:
   - the home screen
   - the destination search
   - **the fare list with all ride types** (scroll and dump again if it doesn't fit)
   - any popup or promo that appears
   - the screen you get when logged out, if you can (log out of one app once, dump the login
     screen, then log back in)
5. Share the files (Files app → Downloads → Hail). Dumps contain whatever was on screen
   (your name, saved places), so check them before sharing and delete them afterwards.

### Ride types
Pick **Auto / Bike / Cab / Cab XL** in the chat's **Ride type** dropdown, or say it ("rickshaw", "big
cab"). If you don't, Hail asks with four buttons. You only see that type's rides, best first, with
when the prices were read ("Live prices from the apps · checked 12:16 PM").

### Voice
Tap 🎤 in the chat (or long-press the Hail icon → **Speak to Hail**) and say e.g. "Koramangala,
cheapest" or "Koramangala jaana hai, sasta wala". Hail reads out the top three rides and asks
"Which one?". Say "Ola auto", "the first one" or "haan", or tap a ride. "None of these" or "nahi"
cancels. The phone does the speech recognition (on the phone itself where supported). Hail
never receives audio.

### Test the chat loop without ride apps
Debug → tick **Simulate fares** (debug builds only). Chat: "Powai to Andheri station, cheapest"
→ you get a list of rides with test fares → tap one → Hail opens that app.

## UI screenshots (no phone needed)
`docs/ui/` has the app's real screens, rendered from its code with Robolectric and fed a real
backend conversation (`tools/make_ui_fixtures.py`). To regenerate them:
`cd android && ./gradlew testDebugUnitTest -PhailScreens=1 --tests '*UiScreenshots*'` →
`android/app/build/ui-screens/`.

## Docs
- `docs/protocol.md`: every message type, with examples
- `docs/decisions.md`: why things are the way they are
- `CLAUDE.md`: engineering brief and non-negotiable rules
