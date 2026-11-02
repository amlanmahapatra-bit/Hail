# Ride-app readers: what the recordings show

Source: the founder's screen recordings of Rapido (86 s), Uber (118 s) and Ola (37 s), Bangalore,
2026-11-02 12:10–12:16 IST. **Recorded on an iPhone (iOS apps).** The Android apps usually use the
same wording, but their accessibility trees can differ, so every reader is marked
`draft_from_ios_recordings` until checked against Android dumps.

Implemented in: `backend/app/readers_config.json` (catalog, screen markers, patterns; served to
phones and fixable without a new APK), `android/.../FareReader.kt` (parser), tests in
`android/app/src/test/.../FareReaderTest.kt` (fixtures transcribed from the recordings), and
`tools/recorded_fares_blr_2026-11-02.json` (the fares seen, used by backend tests and the fake phone).

## Fare-list layouts

| | Uber | Ola | Rapido |
|---|---|---|---|
| Screen marker | "Choose a ride" / "Rides we think you'll like" | "Book Any" row, ride taglines | "Book now" + "Bargain & Book" / category chips |
| Row | name, seats · "12:22 PM · 3 min" · tagline, price right | name, tagline; ETA "3 min" under the icon; price right | name (+ badge), "3 min away • Drop 12:25 pm • 3", price right |
| Price | "₹49.40", struck "₹52.00" below | "₹46", struck "₹51" below, coupon tag | "₹49" |
| Loading | "Gathering options" sheet, then rows with grey price boxes | rows with "…" for prices (≈2 s) | shimmer rows |
| Rows to skip | 5 rentals ("1 hr/10 km"), Parcel Bike, Uber Pet | Book Any (a range across Mini/Sedan/Plus), Hourly Rental, Parcel | Bike Rated by Women (specialty), Auto Pet; **every ride repeated** under Women Preferred / Priority Services / Extra Space / Pet Friendly |
| Below the fold | yes, about 15 more rows | yes, about 6 more | yes |
| Logged out | "Go anywhere with Uber", "Continue with Mobile" | not recorded | "What's your number?", "Enter Mobile Number" |
| After booking | "Confirming your ride", "Ride requested" | not recorded | "Searching in progress", "Start ride with PIN" (shows driver details: never read) |

Reader rules that follow from this:
1. **Payable price = the topmost price in the row.** The struck-through original sits below it, and
   accessibility exposes both as plain text, with no strike-through flag.
2. Rows are grouped by position: a row runs from one catalog name down to the next.
3. **First occurrence wins**, which de-duplicates Rapido's repeated sections.
4. **Wait for prices to settle** (two identical reads about 0.7 s apart), then scroll to read
   rides below the fold.
5. **Anchor only on catalog names, on a recognised fare-list screen.** Home screens also show
   "Auto" or "Bike Direct" tiles, which must not be read as fares.
6. If an Android app merges a whole row into one content description, read the name and price from
   that string instead.

## Fares seen (one trip, close to like-for-like)

Drop: FF21-Maya-Student Hostel, Begur (about 1.4 km). Pickups differed slightly per app. Current prices:

- **Bike:** Ola Bike ₹40 (1 min) · Rapido Bike Direct ₹40 · Uber Bike Saver ₹40.96 · Rapido Scooty ₹44 · Uber Scooty ₹44.18
- **Auto:** Ola ₹46 · Rapido Auto / Auto Lite ₹49 · Uber ₹49.40
- **Economy cab:** Rapido Cab Non AC ₹101 · Uber Go Non AC ₹109.15 · Rapido Cab AC ₹108 · Ola Mini Non AC ₹112 · Ola Mini ₹118 · Uber Go AC ₹118.75
- **Sedan:** **Uber Premier AC ₹64.98** (promo, was ₹144.98) · Ola Prime Sedan ₹124 · Ola Prime Plus ₹131 · Rapido Cab Premium ₹132
- **SUV / 6-seater:** Ola Prime SUV ₹139 · Rapido Cab XL ₹164 · UberXL ₹189.38 · Uber Premier XL ₹238.60

Product insights:
- **Promos break tier order.** A sedan was the cheapest cab of all. Comparing all tiers, not just the
  one the user asked for, finds real savings.
- **Short trips save little.** Bikes and autos differed by ₹1–4 on 1.4 km. Visible savings come from
  cabs and SUVs, and probably from longer trips. Keep "You saved ₹X" honest.
- **Each app lists 10–20 products.** Savings are only computed within a comparable class (economy AC
  vs economy AC, SUV vs SUV), never Black vs Mini.
- **Discounts are already applied in the shown price** (Ola WELCOME coupon, Uber "5% promo"). Hail
  compares what the user would actually pay *now*, which can depend on the user's own coupons.

## Reaching the fare list (Navigator.kt)

1. Try the deep link if the app has one (Uber; Ola from its public docs) and we have coordinates.
   If the fare list shows within 8 s, read it.
2. Otherwise open the app fresh and repeat, one step at a time, based on what's on screen:
   - logged out → stop with `not_logged_in`; ride in progress → stop (never touch it)
   - search screen → type the pickup (only if not the current location and there are two fields),
     then the destination, then tap the suggestion whose words match (at least 2/3 of the query)
   - home screen → tap a recent destination that matches exactly, else the search bar
     (texts from the recordings), else dismiss an allow-listed popup
   - never tap Book/Confirm/Request/Choose/Pay/Cancel/Change; give up after 24 steps or 3 repeats
3. Taps go to the node or its clickable parent, else a gesture at its centre. Typing uses set-text,
   else paste (clipboard cleared after).

## Still unknown (needs an Android phone)

- Whether the Android apps expose these texts as separate nodes or merged descriptions (the reader
  handles both), and whether "₹" appears as "₹", "Rs" or "Rupees" (the pattern accepts ₹, Rs and INR).
- Whether scrolling works the same way on each list (`ACTION_SCROLL_FORWARD` on the largest scrollable).
- Ola's logged-out screen, and any popups (none appeared in these recordings).
- Whether Ola's documented deep link works without an Ola-issued `utm_source` token. Navigation covers
  it if not.
- The Uber and Rapido destination-search screens (the recordings tapped recents instead). The navigator
  handles them generically (editable fields + matching suggestion), and a dump confirms it.
