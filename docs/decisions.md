# Decisions log

Newest first. Each entry: decision, why, and what would make us revisit it.

## 2026-11-10: Hail becomes a proper app (Compose UI, invite-only beta, Firebase Analytics)
- **Founder decision, after the 6 Nov mentor call:** Hail is an app first, with the agent
  underneath. Invite-only sign-up ("request beta"), no city limit, branding, analytics. UI polish
  comes off the never list.
- **Android first.** Only Android can read and tap the ride apps; iPhone follows.
- **Jetpack Compose** (Google, first-party) replaces the code-built chat screen: onboarding
  (welcome, invite code, privacy, connect apps), then Home / Rides / Savings / Profile with the
  trip screens (listening, checking your apps, results, done) on top. The engine is unchanged:
  `AgentRunner`, the accessibility reader, voice and the confirmation gate. This ends the
  "zero third-party Android libraries" rule for UI; the APK grows from ~0.9 MB to ~3 MB (release).
- **Invite codes, not OTP.** Free; no SMS provider. `POST /v1/devices` takes `invite_code`, checked
  only when `REQUIRE_INVITE=true` (off in dev and tests). `POST /v1/invite-requests` stores the
  contact a person typed in. `tools/invites.py` creates codes and approves requests. The code is
  not stored on the device row, so a device can't be traced back to its request.
- **Firebase Analytics (founder's choice).** Trade-off stated: it is a Google SDK, which sits
  awkwardly with "only fares leave your phone". So it is limited in code (`Analytics.kt`): coarse
  events only (request started, options shown, ride opened, invite redeemed) with an allowlist of
  parameters (category, count, app, savings bucket, live). Never places, coordinates, fares,
  screen text, voice, contacts or invite codes. Advertising ID, ad personalisation and SSAID are
  off in the manifest. The privacy screen says Hail counts usage. Delete my data resets the
  analytics ID. Microsoft Clarity (session recording) was not added: it can capture what is on
  screen, which conflicts with how Hail treats screen data.
- Firebase only activates when `android/app/google-services.json` is present (not committed;
  CI can write it from a secret). Without it the app builds and runs, and analytics is a no-op.
- **Ride history and savings live on the phone** (`TripLog`), not on the backend. Test prices
  never count as savings.
- Revisit: OTP if invite codes leak; a privacy-first analytics tool if Firebase becomes a trust
  problem with users; pan-India needs a real geocoder (the gazetteer covers 2 cities).

## 2026-11-03: iPhone without the $99 Apple Developer Program (free Apple ID sideloading)
- **Founder constraint:** no budget for the Apple Developer Program. Apple lets anyone run their
  own builds on their own iPhone with a free Apple ID. Without a Mac, tools such as SideStore,
  AltStore or Sideloadly do that signing. Limits: apps expire after 7 days and must be re-signed
  (SideStore can refresh on the phone), about 3 sideloaded apps at once, 10 App IDs per 7 days
  (Hail uses 2: app + screen reader).
- **Free Apple IDs can't use App Groups**, so the app and the screen reader no longer share files.
  The prices ride inside the screen reader's local notification ("Got 6 Uber prices"), as catalog
  keys + numbers, validated on arrival (`HailCore/FareHandoff`). Tapping it, or reopening Hail
  (which collects delivered notifications), hands them over. The screen reader detects which app
  is on screen itself (`AppDetector`). It stops itself after all three apps or 5 minutes.
- CI builds an **unsigned .ipa** (`hail-ios-unsigned-ipa`) that the sideloading tool signs with the
  user's free Apple ID. TestFlight (paid) stays possible later with no code change.
- **Honest limits:** fine for the founder and a handful of testers. For a 50-person iPhone pilot,
  making everyone sideload and re-sign weekly is real friction; that's when the $99 (TestFlight)
  pays for itself. Free alternative to ask about: whether Mesa School can add students to an
  education developer team.

## 2026-11-03: iPhone app with screen reading (replaces "iOS lite = Shortcut")
- **Founder decision:** iPhone is a priority market. iOS forbids an app from reading or tapping
  another app, so the Android method can't work. The one legitimate way to see another app's
  prices is **iPhone screen broadcast** (ReplayKit, as used by Zoom screen sharing). The user starts
  it with one tap, and a red indicator shows while it's on. Hail's broadcast extension reads frames
  **on the iPhone** with Apple's text recognition (Vision) and drops them at once. Only parsed fares
  (catalog keys + numbers) reach the app. No images or screen text are stored or uploaded.
- **Flow (assisted, about 3-4 taps more than Android):** Hail opens Uber/Ola on the trip via their
  public deep links (Rapido has none, so the destination is copied for pasting). The extension
  reads the fare list (the user may scroll), then a notification "Got Uber's prices, tap to
  continue" brings the user back. Same for Ola and Rapido, then the same options card. The pick
  opens the winning app on the trip, and the user taps Book. iPhone can't pre-select the ride (no
  tapping other apps).
- Same backend protocol and gate: the iPhone registers `platform: ios` with
  `can_check_fares: true`. Readings are labelled `<app>.ios_ocr.draft1` and are live.
- **Shared logic** (`ios/HailCore`: fare reader, ride types, voice pick, frame accumulator) is
  pure Swift, tested on Linux (`swift test`) with fixtures from the founder's **iPhone**
  recordings, the exact screens the extension reads. The app and extension (SwiftUI, ReplayKit,
  Vision, Speech, AVFoundation, App Intents/Siri) build on a macOS GitHub runner.
- **Risks:** (1) broadcast extensions have a ~50 MB memory limit, and Vision on full frames must
  fit; (2) OCR may misread "₹" (needs device testing); (3) Apple review of a screen-reading app is
  untested territory. TestFlight external testing gets a light review, the App Store a full one;
  (4) 60-second freshness: the user must tap the notification promptly.
- **Cost:** Apple Developer Program ($99/yr) is required to install on any iPhone (TestFlight).
  There's no way around it without a Mac. macOS CI minutes are limited, so the iOS workflow runs
  only when `ios/` changes.

## 2026-11-02: Premium category, remembered ride type, favourites, 1-minute prices, ride pre-selected
- **Categories:** Auto · Bike · Cab (economy: non-AC, AC, priority, luggage) · **Premium** (sedans
  and luxury: Uber Premier AC and Black, Ola Prime Sedan and Prime Plus, Rapido Cab Premium) · Cab XL.
  "sedan", "premium", "black" and "prime" map to Premium; "prime SUV" still maps to Cab XL.
- **The dropdown remembers the last ride type used** (picked, tapped or spoken). Hail only asks
  again if the user sets it back to "Any (ask me)".
- **Favourites:** `GET/POST/DELETE /v1/favorites`, per device, max 20, de-duplicated by label,
  deleted by `DELETE /v1/me`. One-tap ♥ buttons above the text box (long-press removes), and
  "♡ Save <place> to favourites" on each results card. Tapping one sends `destination` with its saved
  place, so there's no lookup. Privacy: home and office are sensitive, so they are stored per device
  only, never shared or analysed, and the consent screen says so. Alternative if users push back:
  keep them on the phone only (no sync).
- **Live prices: 60 seconds** (`MAX_PRICE_AGE_SECONDS=60`). Each app's prices must reach the
  server within a minute of being read, or they're rejected. The card shows the time to the
  second ("Live prices · updated 12:16:05 PM", the oldest price shown). Reading three apps takes
  time, so the first app's prices can be about a minute old when the list appears. The ride app
  shows its own final price before the user taps Book.
- **Hand-off:** after the user picks, Hail opens the winning app on the same trip (deep link or
  navigation, pickup and drop filled) and **taps the chosen ride's row** (e.g. "Auto"). The user
  only taps that app's Book button. Selecting a row books nothing, and `NEVER_TAP` still blocks
  Book/Confirm/Choose.

## 2026-11-02: Ride categories; Hail asks which one; live prices only
- **Founder decision:** don't show all 30 rides. The user picks a category: **Auto** (rickshaw),
  **Bike**, **Cab** (economy, AC, sedans, priority, luxury) or **Cab XL** (SUV / 6-seater). The
  options are only that category, best first, top 4 plus "Show more".
- It comes from, in order: words in the request ("rickshaw", "big cab", "badi gaadi", "XL"; checked in
  code), the chat's **Ride type dropdown** (Any = ask), or **Hail asks** "Which ride would you like:
  auto, bike, cab or cab XL?" with four buttons (tap, say, or type). The ask happens in code when the
  model tries to check fares without a category, so it costs no extra model call. A new destination
  asks again unless the dropdown is set.
- **Tokens:** the model now sees only the chosen category's options, in a compact form. Measured with
  Gemini on the recorded fares: 7,182 input tokens (all 30 rides) → 4,602 (Cab XL) / 5,299 (Cab).
  After an ask, it's 6,407 (one extra model step). Most of what's left is the fixed prompt plus tool
  definitions.
- **Live prices:** fares come only from the ride apps on the user's phone, read at request time.
  There is no public Uber, Ola or Rapido price API, and no caching. The backend now rejects readings
  older than 3 minutes (`MAX_PRICE_AGE_SECONDS=180`) or from the future. Cards show "Live prices from
  the apps · checked 12:16 PM". Simulated and recorded test fares are labelled "Test prices, not live"
  (`live: false`) and can't pass as live.

## 2026-11-02: Voice mode (on-device speech, spoken picks)
- **Founder decision:** voice is a core experience. The whole flow is hands-free: speak, Hail
  checks the apps, reads out the top 3, you say "Ola auto" / "the first one" / "haan", Hail opens it.
- **Engines:** Android's built-in SpeechRecognizer (on-device when the phone supports it, else the
  phone's standard recognizer) and TextToSpeech, both en-IN. ₹0 per request, no extra libraries, and
  Hail's backend never receives audio, only text, like typing. Revisit with Gemini audio input if
  on-device accuracy on Hinglish or noisy streets is poor in the pilot.
- **Safety rule change:** a spoken pick counts like a tap, but only when `VoicePick` (fixed rules,
  on the phone, never the model) maps it to exactly one option on the card. Unclear or off-card
  ("Ola Prime SUV" when it isn't listed) → Hail asks again, never guesses. Hail says what it's
  opening, and still never books. The gate is unchanged: same single-use token, now with
  `via: "tap" | "voice"` for auditing.
- **No wake word** ("Hey Hail"): Android restricts background mic, and it costs battery and
  privacy. Instead: the 🎤 button, plus a long-press "Speak to Hail" shortcut on the app icon.
- Spoken requests set `input_mode: "voice"`, and the model gets a voice note: expect mis-hearings and
  Hinglish, reply in one speakable sentence. Live test: "Koramangala jaana hai sasta wala" and
  "mujhe whitefield jaana hai jaldi, I have two bags" were both understood correctly.
- The city for ambiguous places ("airport") comes from the phone's location.

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
