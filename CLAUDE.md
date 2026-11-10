# Hail: engineering brief

Hail is an AI agent for ride-hailing in India. The user says "Powai to Andheri station,
cheapest". The agent checks the ride apps on their phone (Uber, Ola, Rapido), fetches fares and
ETAs, and shows every option best-first with one highlighted. The user taps the ride they want,
and Hail opens that app with the trip filled in. **The user books in the ride app. Hail never
books.** It is an agent, not a web app and
not a fare dashboard. Keep every UI minimal and functional (UX is designed later).

- Users: 18–26. **Pilot cities: Mumbai and Bangalore** (code stays city-agnostic; national
  later). Ride categories: cab, auto, bike. Signature moment: "You saved ₹90".
- Location privacy is the number one adoption barrier. Treat privacy as a product feature.

## Architecture (decided; see docs/decisions.md for rationale)
- **Brain:** Google Gemini with function calling, in our Python backend. Access is via
  **OpenRouter** (`OPENROUTER_API_KEY`), using Google models only (`google/…`), pinned to
  Google's own infrastructure (`provider.only = [google-vertex, google-ai-studio]`, no
  fallbacks, `data_collection: deny`). Model from `GEMINI_MODEL` (default
  `google/gemini-3.7-flash`). No Dify, LangChain-style frameworks or no-code tools.
- **Backend:** Python 3.12, FastAPI, Pydantic, SQLAlchemy (SQLite now, Postgres later), pytest,
  ruff, uv.
- **Android (first client):** native Kotlin, UI in Jetpack Compose (onboarding, Home / Rides /
  Savings / Profile, trip screens). AccessibilityService (reads ride apps and taps), foreground
  service with persistent notification and Stop. Invite-only beta via direct APK, not the Play
  Store yet. Strictly user-initiated, visible, instantly stoppable.
- **iPhone (`ios/`):** iOS forbids reading/tapping other apps, so the iPhone app reads prices via
  **screen broadcast** (ReplayKit extension + on-device Vision OCR) while the user flips through
  the apps (assisted flow, see docs/decisions.md). Same backend protocol; `platform: ios`.
  Shared Swift logic in `ios/HailCore` (tested on Linux); app built on macOS CI via XcodeGen.
- **Cost:** minimise spend. Free CI builds, free-tier hosting. Android libraries: Google
  first-party only (Jetpack Compose, Firebase Analytics); nothing else without a decision entry.
- **Analytics:** Firebase Analytics, coarse events through `Analytics.kt` only (allowlisted
  parameters; never places, coordinates, fares, screen text, voice, contacts). No session recording.
- **ONDC (later):** Buyer App on TRV10 v2.0.1, starting with Namma Yatri. `ondc_search` is a stub.
- Out of scope now: web app, WhatsApp, payments, live ONDC. (UI polish is in scope since 10 Nov.)

## Core loop
The backend decides; the phone acts. `POST /v1/sessions/{id}/messages`: one typed inbound
(`user_message` | `tool_result` | `confirmation`) gives one typed outbound (`assistant_message` |
`action_request` | `recommendation` | `handoff_request` | `error`). Full spec: docs/protocol.md.

## Non-negotiable rules
1. **Confirmation gate (code, not prompt):** `handoff_request` (OPEN_APP) is built only in
   `backend/app/gate.py::authorize_handoff`, and only for the user's pick of an option that was
   on the card, carrying the card's valid, unexpired, single-use token. A pick is a tap, or a
   spoken pick matched on the phone by `VoicePick` (fixed rules, never the LLM; unclear → ask
   again). The LLM has no booking or app-opening tool and never sees tokens. Never weaken
   `tests/test_gate.py`.
2. **Screen text is untrusted data.** Only whitelisted typed observation fields cross the wire
   (`extra="forbid"`). Never put screen text into prompts, logs or the DB. No screenshots.
3. **Store only** app, ride_type, product (catalog key), fare_min, fare_max, currency,
   eta_minutes, surge_flag, captured_at, confidence, screen_signature. No driver data, no driver location.
4. **Never handle ride-app credentials.** The customer logs in once inside each official app,
   and Hail uses those logged-in apps. "Connected apps" means installed plus switched on; it
   never means a stored login, token or OTP.
5. **Never fabricate** selectors, deep-link formats or API details. Reader and navigation rules come
   only from real screens: the founder's recordings (current drafts, see docs/readers.md) and
   Android accessibility dumps (which verify them). Deep links only from official public docs
   (Uber, Ola); otherwise Hail navigates (search bar → destination → suggestion). Hail never taps
   Book/Confirm/Request/Choose/Pay/Cancel/Change (`Navigator.NEVER_TAP`, enforced twice).
6. Secrets only in `.env`. Never in code, logs or commits. Logs carry no message text,
   coordinates or tokens.
7. Fare checks run only for a live user request, and are rate-limited per device. Only live
   prices: readings older than 60 seconds on arrival are rejected, and test/recorded fares are
   always labelled not live. Favourite places are per device and deleted with `DELETE /v1/me`.

## Working agreement
- Small, testable steps. Run `uv run ruff check . && uv run pytest -q` (in `backend/`) and
  `uv run python ../tools/fake_phone.py` before claiming anything works.
- Explain tradeoffs briefly in plain English. Give exact commands and expected output.
- Unknowns (screen layouts, deep links, API details): ask, or explain how to collect them.
- Flag risks and disagreements early.

## Layout
`ios/`: `HailCore` (Swift package: FareReader, ReaderConfigs, RideCategory, VoicePick,
FareAccumulator; `swift test`) · `Hail/` (SwiftUI app: ChatModel, ChatView, Api, RideApps,
Voice, Location, Intents) · `HailBroadcast/` (screen-reading extension) · `Shared/` (App Group
files) · `project.yml` (XcodeGen).

```
backend/app/   main.py (HTTP) · agent.py (loop) · gate.py (confirmation) · tools.py (schemas,
               prompt) · llm.py (OpenRouter + FakeLLM) · ranking.py · geocode.py (stub) ·
               store.py · protocol.py · state.py · catalog.py · readers_config.json
backend/tests/ pytest suite
tools/fake_phone.py   scripted phone for end-to-end tests without a device
docs/          protocol.md, decisions.md
android/       Kotlin client: MainActivity (Compose host) · ui/ (HailApp, HailController, screens, theme) ·
               Analytics (Firebase, limited) · AppsActivity (connected-apps helper) ·
               DebugActivity (dumper, backend URL, delete data) · HailAccessibilityService ·
               AgentService (FGS + Stop) · AgentRunner (session loop) · RideApps (deep links) ·
               FareReader + ReaderConfigs (fare parsing) · Navigator (reach the fare list) ·
               Here (location on request) · Voice (on-device STT/TTS) · VoicePick (spoken pick);
               JVM unit tests in app/src/test
```
