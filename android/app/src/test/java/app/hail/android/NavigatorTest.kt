package app.hail.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Screens transcribed from the founder's Ola recording (Bangalore, 2026-11-02), plus safety cases. */
class NavigatorTest {
    private val cfgs = ReaderConfigs.parse(JSONObject(File("../../backend/app/readers_config.json").readText()))
    private val ola = cfgs.getValue("ola")
    private val rapido = cfgs.getValue("rapido")

    private fun n(t: String, l: Int, top: Int, r: Int, b: Int, editable: Boolean = false, focused: Boolean = false) =
        ScreenNode(t, null, l, top, r, b, editable, focused)

    private val goal = NavGoal(dropQuery = "FF21 Maya Student Hostel", pickupQuery = null)

    // 00:06.4: Ola home
    private val olaHome = listOf(
        n("38/2, Esteem Enclave, Sar...", 150, 110, 330, 126),
        n("Where are you going?", 55, 385, 216, 404),
        n("Bira 91 Taproom Koramangala Shop No 3...", 58, 453, 330, 469),
        n("Terminal 1 Kempegowda Int'l Airport Beng...", 58, 501, 336, 517),
        n("Outstation", 30, 625, 92, 640), n("Rentals", 147, 625, 192, 640), n("Bike", 262, 625, 294, 640),
        n("#OlaOnTheMove", 15, 715, 192, 740),
    )

    // 00:09.6: Ola "Destination" search screen, keyboard up, destination field focused
    private fun olaSearch(dropText: String?, results: List<ScreenNode> = emptyList()) = listOf(
        n("Destination", 54, 72, 140, 90), n("Myself", 300, 72, 352, 88),
        n("38/2, Esteem Enclave, Sarvobhogam Nagar, Ar...", 54, 126, 362, 142, editable = true),
        n(dropText ?: "Enter destination", 54, 174, 320, 190, editable = true, focused = true),
    ) + (results.ifEmpty {
        listOf(
            n("Bira 91 Taproom", 54, 240, 157, 256), n("Koramangala Shop No 33 80 Feet Rd Near Wipr...", 54, 262, 368, 278),
            n("Terminal 1", 54, 304, 120, 320), n("Kempegowda Int'l Airport Bengaluru Karnataka...", 54, 326, 362, 342),
            n("Favourites", 15, 392, 92, 408), n("1 more", 50, 438, 95, 454),
        )
    }) + n("Locate on map", 165, 512, 259, 528)

    private val olaResults = listOf(
        n("FF21-Maya-Student Hostel", 54, 241, 250, 257),
        n("Krishna Layout Begur Hobli Bengaluru Karnatak...", 54, 263, 360, 279),
        n("1.4 km", 8, 264, 44, 278),
    )

    private val olaFareList = listOf(
        n("Book Any", 102, 270, 172, 290), n("₹118 - ₹131", 278, 270, 360, 290),
        n("Auto", 102, 345, 137, 365), n("Quickest auto ride in town", 102, 370, 247, 384), n("₹46", 330, 345, 360, 365),
    )

    @Test fun ola_full_route_from_home_to_fare_list() {
        val mem = NavMemory()
        val a1 = Navigator.decide(olaHome, ola, goal, mem)
        assertEquals("open search", (a1 as NavAction.Tap).why)
        assertEquals("Where are you going?", olaHome[a1.index].text)

        val search = olaSearch(null)
        val a2 = Navigator.decide(search, ola, goal, mem) as NavAction.Type
        assertEquals("Enter destination", search[a2.index].text) // the destination field, not pickup
        assertEquals("FF21 Maya Student Hostel", a2.text)

        val typedNoResults = olaSearch("FF21 Maya Student Hostel", results = listOf(n("Searching...", 54, 240, 150, 256)))
        assertTrue(Navigator.decide(typedNoResults, ola, goal, mem) is NavAction.Wait)

        val withResults = olaSearch("FF21 Maya Student Hostel", olaResults)
        val a3 = Navigator.decide(withResults, ola, goal, mem) as NavAction.Tap
        assertEquals("FF21-Maya-Student Hostel", withResults[a3.index].text)

        assertEquals(NavAction.Arrived, Navigator.decide(olaFareList, ola, goal, mem))
    }

    @Test fun recent_destination_on_home_is_used_directly() {
        val a = Navigator.decide(olaHome, ola, NavGoal("Terminal 1 Kempegowda Airport", null), NavMemory()) as NavAction.Tap
        assertEquals("recent destination", a.why)
        assertTrue(olaHome[a.index].text!!.startsWith("Terminal 1"))
    }

    @Test fun recents_not_used_when_pickup_is_elsewhere() {
        val a = Navigator.decide(olaHome, ola, NavGoal("Terminal 1 Kempegowda Airport", "Mesa School of Business"), NavMemory())
        assertEquals("open search", (a as NavAction.Tap).why)
    }

    @Test fun custom_pickup_is_set_before_destination() {
        val g = NavGoal("FF21 Maya Student Hostel", "Mesa School of Business")
        val mem = NavMemory()
        val s1 = olaSearch(null)
        val t1 = Navigator.decide(s1, ola, g, mem) as NavAction.Type
        assertEquals(2, t1.index) // the pickup field (top)
        assertEquals("Mesa School of Business", t1.text)

        val s2 = listOf(
            n("Destination", 54, 72, 140, 90),
            n("Mesa School of Business", 54, 126, 362, 142, editable = true, focused = true),
            n("Enter destination", 54, 174, 320, 190, editable = true),
            n("Mesa School of Business", 54, 241, 250, 257), n("Bannerghatta Rd, Arekere", 54, 263, 360, 279),
        )
        val t2 = Navigator.decide(s2, ola, g, mem) as NavAction.Tap
        assertEquals(3, t2.index)
        assertTrue(mem.pickupDone)

        val s3 = s2.take(3)
        val t3 = Navigator.decide(s3, ola, g, mem) as NavAction.Type
        assertEquals(2, t3.index) // now the destination field
    }

    // Rapido 8.121.0 on Android, Mumbai, from the founder's accessibility dump (2026-11-12):
    // the search screen is Compose and the drop field is NOT marked editable; it is only a node
    // described as "Enter Drop location Input Field...". Bounds and text exactly as dumped.
    private fun d(desc: String, l: Int, t: Int, r: Int, b: Int) = ScreenNode(null, desc, l, t, r, b)
    private fun rapidoSearch(suggestions: List<ScreenNode>) = listOf(
        d("Pickup and Drop Screen", 0, 0, 1080, 2340),
        d("Pickup Location is 116-6, Temple Ln, Anand Vihar, Andheri East, Mumbai. Double tap to change", 181, 278, 944, 414),
        d("Add Stop to drop your friends", 934, 279, 1069, 414),
        d("Enter Drop location Input Field. Enter at least 4 characters to start searching. After entering the Drop location, You will be navigated to the Fare Estimate screen automatically.", 181, 420, 944, 556),
        n("For me", 799, 138, 939, 192),
    ) + suggestions

    @Test fun rapido_labelled_drop_field_is_typed_then_suggestion_tapped() {
        val mem = NavMemory()
        val goal = NavGoal("Bandra station", null)
        val recents = listOf(
            n("Omkar 1973", 136, 618, 378, 672),
            n("Doctor Annie Besant Road, next to Neelam Center, Worli, Mumbai", 136, 683, 933, 728),
        )
        val type = Navigator.decide(rapidoSearch(recents), rapido, goal, mem) as NavAction.Type
        assertEquals(3, type.index)
        assertEquals("Bandra station", type.text)
        // Suggestions not there yet: wait, don't tap an unrelated recent.
        assertTrue(Navigator.decide(rapidoSearch(recents), rapido, goal, mem) is NavAction.Wait)
        val results = listOf(
            n("Bandra station", 136, 618, 432, 672),
            n("Bandra Terminus, Station Road, Bandra East, Mumbai, Maharashtra, India", 136, 683, 933, 728),
            n("Bandra Station (E)", 136, 809, 420, 863),
        )
        val tap = Navigator.decide(rapidoSearch(results), rapido, goal, mem) as NavAction.Tap
        assertEquals("Bandra station", rapidoSearch(results)[tap.index].label)
    }

    @Test fun never_taps_booking_buttons_even_if_they_match() {
        // Rapido "Check your pickup point" after Book now: nothing to do here, must not confirm.
        val confirm = listOf(
            n("Check your pickup point", 66, 640, 240, 656), n("Select a nearby point for easier pickup", 66, 658, 285, 672),
            n("We Work, Bannerghatta Rd", 415, 712, 614, 728), n("Confirm pickup", 527, 794, 634, 812),
        )
        assertTrue(Navigator.decide(confirm, rapido, NavGoal("Confirm pickup", null), NavMemory()) !is NavAction.Tap)
        assertEquals(null, Navigator.bestMatch(listOf(n("Book Depot", 0, 300, 100, 320)), "Book Depot", 0, 0.5))
        assertTrue(Navigator.NEVER_TAP.containsMatchIn("Book now"))
        assertTrue(Navigator.NEVER_TAP.containsMatchIn("Choose Auto"))
        assertTrue(Navigator.NEVER_TAP.containsMatchIn("Cancel Ride"))
        assertTrue(!Navigator.NEVER_TAP.containsMatchIn("Where to?"))
    }

    @Test fun logged_out_and_ride_in_progress_stop_navigation() {
        val login = listOf(n("What's your number?", 403, 128, 585, 146), n("Enter Mobile Number", 477, 207, 630, 223, editable = true))
        assertEquals("not_logged_in", (Navigator.decide(login, rapido, goal, NavMemory()) as NavAction.Fail).status)
        val active = listOf(n("Start ride with PIN", 15, 556, 111, 568), n("Pickup in 6 mins", 30, 480, 152, 498))
        assertEquals("failed", (Navigator.decide(active, rapido, goal, NavMemory()) as NavAction.Fail).status)
    }

    @Test fun gives_up_when_stuck() {
        val mem = NavMemory()
        var last: NavAction = NavAction.Wait("")
        repeat(4) { last = Navigator.decide(olaHome, ola, goal, mem) }
        assertTrue(last is NavAction.Fail)
    }
}
