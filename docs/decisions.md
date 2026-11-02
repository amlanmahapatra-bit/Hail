# Decisions log

Newest first. Each entry: decision, why, and what would make us revisit it.

## 2026-11-02: LLM access through OpenRouter, pinned to Google
- The only key we have is an OpenRouter key. We call Gemini through OpenRouter's
  OpenAI-compatible `/chat/completions` with function calling (plain `httpx`, no vendor SDK),
  not the `google-genai` SDK.
- Every request sets `provider: {only: [google-vertex, google-ai-studio], allow_fallbacks:
  false, data_collection: "deny"}`, so only Google's infrastructure serves it, and only
  endpoints that don't train on the data.
- Model from `GEMINI_MODEL`, default `google/gemini-3.7-flash` (fast, cheap, tool calling
  verified end to end).
- Assistant messages are stored verbatim (including `reasoning_details`), because Gemini
  needs its thought signatures returned on the next call.
- Revisit if we get a direct Gemini API key: add a second `LLMClient` implementation. The agent
  only depends on the `LLMClient` protocol.

## 2026-11-02: Pilot cities: Mumbai and Bangalore; built city-agnostic
- City lives in data (geocoder, reader config), not code. Geocoding is a stub list of
  landmarks for both cities until we pick a real geocoder (privacy + cost decision pending).
- Bangalore is where Namma Yatri (ONDC) is strongest, which suits the later ONDC phase.

## 2026-11-02: BOOK is its own response type (`booking_request`)
- The brief lists BOOK as an action request, and lists `booking_request` as a response type.
  We use `booking_request` exclusively for BOOK, and `action_request` only for CHECK_FARES.
  This gives booking a separate code path on both sides, and `BookingRequest` is constructed
  in exactly one place (`gate.authorize_booking`).

## 2026-11-02: Confirmation is a server-issued single-use token
- The confirmation gate requires a typed `confirmation` carrying a random token issued with the
  recommendation (hash stored, 90 s expiry, single use, invalidated by any new user message).
  The model has no booking tool and never sees the token. Typed "yes" never books.
- Tests: `backend/tests/test_gate.py` (forged and expired tokens, option swaps, reuse,
  cross-session, typed confirmations, hostile model, screen-text injection).

## 2026-11-02: The model sees ids, not raw data
- Tool results to the model carry place_ids and labels (no coordinates), and option_ids with
  typed fare fields (no screen text). `rank_options` reads stored observations instead of taking
  fares from the model, so the model cannot invent prices.
- `request_fare_check` takes `place_id`s from `geocode_place`, never model-written coordinates.

## 2026-11-02: Ranking and savings
- Fares are ranges, so we use the midpoint everywhere.
- cheapest/fastest are strict (lexicographic: fare then ETA, or ETA then fare). balanced is
  a 50/50 normalised mix. Surge counts as 5% dearer.
- Late night (user says so, or 22:00–05:00 IST): bikes excluded unless explicitly requested;
  autos weighted 20% dearer and 4 min slower. Luggage excludes bikes.
- Savings = highest fare among comparable options checked − fare of the chosen option, where
  comparable means the same ride type in the same fare-check round. Not shown if ≤ ₹0.

## 2026-11-02: Rate limit and fare-check pacing
- One CHECK_FARES action per app, sent sequentially (the phone can only drive one app at a
  time). Max `FARE_CHECKS_PER_HOUR` (default 30) app checks per device per rolling hour. Over
  the limit, that app is skipped and reported to the model as `rate_limited`.

## 2026-11-02: Storage
- SQLAlchemy 2.0 with SQLite now; Postgres is a `DATABASE_URL` change. Session state is one JSON
  column with optimistic locking. Fare observations are stored as whitelisted columns only.
  `DELETE /v1/me` removes everything for a device.

## 2026-11-02: Hosting
- Week 1: run locally and expose to the phone with a Cloudflare quick tunnel (HTTPS, free).
- Pilot: one small VM in India (DigitalOcean BLR1 or GCP asia-south1) running the Docker image,
  with SQLite on the VM disk plus nightly backups, and Caddy for HTTPS. Reason: SQLite needs a
  persistent disk, which rules out scale-to-zero platforms until we move to Postgres. Data
  stays in India.
- Revisit when we move to Postgres: Cloud Run plus Cloud SQL in asia-south1.
