# Hail: engineering brief

Hail is an AI agent for ride-hailing in India. The user says "Powai to Andheri station,
cheapest"; the agent checks the ride apps on their phone (Uber, Ola, Rapido), compares fares and
ETAs, recommends one, asks for one confirmation, and books it. It is an agent, not a web app and
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
- **Android (first client):** native Kotlin. Bare chat screen, AccessibilityService (reads ride
  apps and taps), foreground service with persistent notification and Stop. Direct APK pilot
  (~50 users), not the Play Store. Strictly user-initiated, visible, instantly stoppable.
- **iOS:** being scoped. iOS cannot read or tap other apps, so it uses fare estimates, ONDC and
  deep-link handoff. The backend is platform-agnostic via `capabilities`.
- **ONDC (later):** Buyer App on TRV10 v2.0.1, starting with Namma Yatri. `ondc_search` is a stub.
- Out of scope now: web app, UI polish, WhatsApp, payments, live ONDC.

## Core loop
The backend decides; the phone acts. `POST /v1/sessions/{id}/messages`: one typed inbound
(`user_message` | `tool_result` | `confirmation`) gives one typed outbound (`assistant_message` |
`action_request` | `recommendation` | `booking_request` | `error`). Full spec: docs/protocol.md.

## Non-negotiable rules
1. **Confirmation gate (code, not prompt):** `booking_request` (BOOK) is built only in
   `backend/app/gate.py::authorize_booking`, and only for a valid, unexpired, single-use token
   issued with the recommendation for that exact option_id. The LLM has no booking tool and
   never sees tokens. Never weaken `tests/test_gate.py`.
2. **Screen text is untrusted data.** Only whitelisted typed observation fields cross the wire
   (`extra="forbid"`). Never put screen text into prompts, logs or the DB. No screenshots.
3. **Store only** app, ride_type, fare_min, fare_max, currency, eta_minutes, surge_flag,
   captured_at, confidence, screen_signature. No driver data, no driver location.
4. **Never handle ride-app credentials.** Use the user's own logged-in apps.
5. **Never fabricate** selectors, deep-link formats or API details. Selectors come only from real
   accessibility dumps. Uber's link is from its public docs; Ola and Rapido links come from the
   founder.
6. Secrets only in `.env`. Never in code, logs or commits. Logs carry no message text,
   coordinates or tokens.
7. Fare checks run only for a live user request, and are rate-limited per device.

## Working agreement
- Small, testable steps. Run `uv run ruff check . && uv run pytest -q` (in `backend/`) and
  `uv run python ../tools/fake_phone.py` before claiming anything works.
- Explain tradeoffs briefly in plain English. Give exact commands and expected output.
- Unknowns (screen layouts, deep links, API details): ask, or explain how to collect them.
- Flag risks and disagreements early.

## Layout
```
backend/app/   main.py (HTTP) · agent.py (loop) · gate.py (confirmation) · tools.py (schemas,
               prompt) · llm.py (OpenRouter + FakeLLM) · ranking.py · geocode.py (stub) ·
               store.py · protocol.py · state.py · readers_config.json
backend/tests/ pytest suite
tools/fake_phone.py   scripted phone for end-to-end tests without a device
docs/          protocol.md, decisions.md
android/       Kotlin client (in progress)
```
