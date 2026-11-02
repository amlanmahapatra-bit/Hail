# Hail

One request, every cab, best price. An AI agent that compares Uber, Ola and Rapido on your
phone, recommends the best ride, and books it after one tap.

Status: Week 1. The backend agent loop and the confirmation gate work end to end with a fake
phone. The Android app builds: consent screen, accessibility service, screen dumper,
Stop notification and chat. Fare reading waits on real screen dumps.

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
uv run pytest -q             # expect: "33 passed"
```

## Acceptance test: fake phone

```bash
cd backend
uv run python ../tools/fake_phone.py                    # offline rule-based agent, no key needed
uv run python ../tools/fake_phone.py --llm openrouter   # real Gemini via OpenRouter
```
Expected final lines:
```
FINAL: Booked: Rapido bike. You saved ₹15.
PASS: 3 fare checks -> recommendation -> forged confirmation refused -> BOOK
```
The fares are canned test data, not real prices.

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
The APK lands in `android/app/build/outputs/apk/debug/`.

### First run on the phone
1. Open Hail → read the consent screen → **I agree** → Android Accessibility settings →
   **Hail ride comparison** → turn on.
   - If it's greyed out or says "Restricted setting" (Android 13+ sideloaded apps): App info →
     ⋮ (top right) → **Allow restricted settings**, then turn it on again.
2. Allow notifications (needed for the Stop button).
3. **Debug** → set **Backend URL** to your tunnel URL (see "Run the server"), then **Save URL**.

### Collect a Rapido screen dump (what we need first)
1. Debug → **Start dump mode**. A "Hail dump mode" notification appears.
2. Open Rapido and enter a trip (e.g. Powai → Andheri station) until the fare list shows.
3. Pull down notifications → **Dump screen**. The notification says
   `Saved Downloads/Hail/hail-dump-rapido-….json`.
4. Repeat for: the home screen, the destination search, the fare list, any popup or promo, and
   the booking/confirm screen.
5. Share the files (Files app → Downloads → Hail). Dumps contain whatever was on screen
   (your name, saved places), so check them before sharing and delete them afterwards.

### Test the chat loop without ride apps
Debug → tick **Simulate fares** (debug builds only). Chat: "Powai to Andheri station, cheapest"
→ you get a recommendation card with test fares → Confirm. Booking always reports "didn't go
through" for now, because the booking tapper isn't built yet.

## Docs
- `docs/protocol.md`: every message type, with examples
- `docs/decisions.md`: why things are the way they are
- `CLAUDE.md`: engineering brief and non-negotiable rules
