# Decisions log

Newest first. Each entry: decision, why, and what would make us revisit it.

## 2026-11-02: Reach the fare list by navigating, not only by deep links
- The prototype's deep links are lost. Rapido has no public format. Ola has an official one
  (developers.olacabs.com/docs/deep-linking), but it requires an Ola-issued `utm_source` token we
  don't have. So Hail **navigates like a person**: it opens the app fresh, taps the search bar
  ("Where to?" / "Where are you going?" / "Where do you want to go?", from the recordings), types
  the destination, and taps the suggestion whose words match. If the destination is in the home
  screen's recents, it taps that directly.
- Deep links are a shortcut: Uber (public docs) and Ola (public docs, placeholder `utm_source`) are
  tried first. If the fare list isn't on screen within 8 s, Hail navigates instead.
- **Safety:** the navigator and the executor both refuse to tap anything starting with Book,
  Confirm, Request, Choose, Pay, Cancel or Change. Popups are dismissed only with a short allow-list.
- When the user picks a ride, Hail navigates to that trip's fare list in the chosen app and stops.
  The user taps Book.
- **Pickup:** by default it is where the user is. The app sends its last known location only with
  a live request, and the ride app keeps its own GPS pickup (`pickup_is_current_location`). A named
  pickup is typed into the pickup field when the search screen has one; otherwise the fares are
  marked low-confidence.
- **Places Hail can't geocode are passed by name**, and each app's own search resolves them. Free,
  and no extra data goes to a geocoder. Deep links are skipped for them, since they need coordinates.
- Debug "Test fare check" runs one app's check on the phone with no backend, using the reader
  config bundled into the APK.

## 2026-11-02: Readers drafted from the founder's screen recordings (iOS)
- The recordings gave real screen text, layouts and flows for all three apps (see
  docs/readers.md). Readers are written from them and marked `draft_from_ios_recordings`. They are
  not guesses, but they are not Android-verified either. The first Android dumps confirm or fix
  them, through config where possible.
- Observations now carry a product **key** from the catalog (e.g. `uber_go_ac`). The backend accepts
  only known, non-excluded keys and maps them to trusted display names, so screen text still never
  crosses the wire.
- Savings are computed within a **comparable class** from the catalog (economy AC, sedan, SUV,
  ...), not just cab/auto/bike, so Black vs Mini Non AC never counts as a "saving".
- Max observations per app raised from 12 to 30: Uber showed 11 rideable products plus rentals.

## 2026-11-02: Hail fetches fares; the customer chooses; the customer books in the app
- **Founder decision.** Hail checks every connected app and lists all options best-first, with
  one highlighted. The customer taps any option, and Hail opens that app with the trip filled in
  (OPEN_APP). The customer taps Book themselves. Hail never books.
- Why: the customer stays in control. It is far less fragile (no booking tapper fighting
  payment prompts and popups), has lower terms-of-service risk with the ride apps, and works
  the same way for iPhone later.
- Protocol: `booking_request`/BOOK replaced by `handoff_request`/OPEN_APP. The card carries one
  token covering all its options, and `confirmation.option_id` is whichever option was tapped.
  The tools `recommend_ride` and `request_confirmation` merged into `present_options`, which
  also means one fewer model step.
- The gate still applies: no app is opened for booking without the customer's tap on that card.
- Savings: shown per option on the card, and as "You save ₹X" when the chosen app is opened. We
  can't confirm they actually booked it yet; detecting the ride app's booked screen is a later
  step.

## 2026-11-02: "Connected apps" means the customer's own logged-in apps, never stored logins
- The customer logs in once inside each official Uber/Ola/Rapido app, as they already do, and
  stays logged in. Hail reuses that, so there is no repeated login and no login inside Hail.
- The Connected apps screen shows installed or not (with a Play Store link), a "Use with Hail"
  switch, and "Open to log in". The phone sends `available_apps`, and only those are checked.
- We do not store ride-app passwords, OTPs or session tokens. Reasons: security liability,
  the apps' terms, OTP-based logins, and no public booking APIs for Ola/Rapido.
- Later, once we have dumps of each app's login screen, a fare check that lands on a login
  screen returns `not_logged_in`, and Hail tells the customer which app to open.

## 2026-11-02: Lowest-cost toolchain (no Mac, minimal spend)
- **Android builds: free.** GitHub Actions builds the debug APK on every push and attaches it
  to the run. Download it from the run page, then install. The debug signing key is committed
  (debug only) so each build installs over the last one.
- **Android app: zero third-party libraries.** Platform Views, HttpURLConnection and org.json
  only. This means a small APK (~0.9 MB), fast builds, and fewer things to break or update.
  UI is code-only and bare on purpose.
- **iPhone lite without a Mac:** v0 is an **Apple Shortcut** ("Hey Siri, Hail"). It needs no
  Mac and no $99 developer account, and is shared by iCloud link. The Shortcut calls our
  backend over HTTPS, shows the comparison, and opens the chosen app via its coordinate deep
  link. A native iOS app (App Intents, ONDC booking) waits until the pilot justifies the Apple
  Developer Program ($99/yr) plus cloud macOS build minutes (Codemagic free tier or GitHub
  macOS runners). Nothing iOS-specific is built yet.
- **Hosting:** Week 1 is free (laptop + Cloudflare quick tunnel). Pilot target is the Oracle Cloud
  Always Free VM in the Mumbai region ($0, data in India). Fallback if Oracle has no capacity
  or reclaims an idle instance: a ~$5/month VPS in Bangalore. The Docker image is the same
  either way.
- **LLM cost, measured** for one full request ("Powai to Andheri station, cheapest"; 4
  model calls):
  - `gemini-3.7-flash`: 4.4k input / 0.3k output tokens ≈ $0.0046 (≈ ₹0.40)
  - `gemini-3.5-flash-lite`: 4.1k / 0.2k ≈ $0.0016 (≈ ₹0.14)

  Both picked the right ride. At 50 users × 2 rides/day, that is ≈ $14/month (flash) or
  ≈ $5/month (flash-lite). We keep flash as the default for reliability; switching is one
  env var.

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
