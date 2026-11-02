package app.hail.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fixtures are transcribed from the founder's screen recordings (Bangalore, 2026-11-02,
 * iOS apps; frames at 384x848). Text is exactly as shown on screen; bounds are measured from
 * the frames. They are NOT Android accessibility dumps: when real dumps arrive, add them as
 * fixtures alongside these.
 */
class FareReaderTest {

    private val configs: Map<String, AppReaderConfig> = ReaderConfigs.parse(
        JSONObject(File("../../backend/app/readers_config.json").readText())
    )
    private val ola = configs.getValue("ola")
    private val uber = configs.getValue("uber")
    private val rapido = configs.getValue("rapido")

    private fun n(text: String, l: Int, t: Int, r: Int, b: Int) = ScreenNode(text, null, l, t, r, b)
    private fun fares(list: List<Fare>) = list.associate { it.productKey to (it.fareMin to it.etaMinutes) }

    // ---------- Ola: fare list at 00:24.8 of the Ola recording ----------
    private val olaFareList = listOf(
        n("38/2, Esteem Enclave, Sarvobhogam N...", 47, 165, 305, 182),
        n("FF21-Maya-Student Hostel, Krishna La...", 47, 213, 303, 230),
        n("Now", 337, 199, 362, 213),
        n("Book Any", 102, 270, 172, 290), n("Mini, Prime Sedan, Prime Pl...", 102, 294, 262, 308),
        n("₹118 - ₹131", 278, 270, 360, 290), n("₹130 - ₹145", 290, 294, 360, 308), n("3 min", 36, 298, 68, 314),
        n("Auto", 102, 345, 137, 365), n("Quickest auto ride in town", 102, 370, 247, 384),
        n("₹46", 330, 345, 360, 365), n("₹51", 338, 370, 360, 384), n("1 min", 36, 370, 64, 384),
        n("Bike", 102, 420, 134, 440), n("Beat the traffic on a bike", 102, 445, 239, 459),
        n("₹40", 330, 420, 360, 440), n("1 min", 36, 445, 64, 459),
        n("Priority", 102, 495, 157, 515), n("Priority Pickup", 102, 520, 183, 534),
        n("₹124", 324, 495, 360, 515), n("₹137", 332, 520, 360, 534), n("1 min", 36, 520, 64, 534),
        n("Mini", 102, 570, 133, 590), n("Comfy, economical cars", 102, 595, 235, 609),
        n("₹118", 324, 570, 360, 590), n("₹130", 332, 595, 360, 609), n("4 min", 36, 595, 66, 609),
        n("Prime Sedan", 102, 645, 196, 665), n("Top sedans", 102, 670, 165, 684),
        n("₹124", 324, 645, 360, 665), n("₹137", 332, 670, 360, 684), n("3 min", 36, 670, 66, 684),
        n("Cash", 75, 735, 107, 751), n("WELCO...", 176, 735, 238, 751), n("Personal", 302, 735, 358, 751),
        n("Book Any", 158, 790, 228, 810),
    )

    @Test fun ola_fare_list() {
        assertEquals(ScreenState.FARE_LIST, FareReader.state(olaFareList, ola))
        val got = fares(FareReader.parse(olaFareList, ola))
        assertEquals(
            mapOf(
                "ola_auto" to (46.0 to 1), "ola_bike" to (40.0 to 1), "ola_priority" to (124.0 to 1),
                "ola_mini" to (118.0 to 4), "ola_prime_sedan" to (124.0 to 3),
            ),
            got,
        ) // "Book Any" (a range across cab types) is excluded; struck-through prices ignored
    }

    @Test fun ola_still_loading_prices_reads_nothing() {
        val loading = olaFareList.filterNot { it.text!!.startsWith("₹") }
        assertTrue(FareReader.parse(loading, ola).isEmpty())
    }

    // ---------- Uber: "Choose a ride" at 00:33.6 of the Uber recording ----------
    private val uberFareList = listOf(
        n("Earning 10% credits", 70, 82, 217, 99), n("5% promo", 239, 82, 315, 99),
        n("Rides we think you'll like", 15, 145, 215, 162),
        n("Auto", 102, 190, 142, 208), n("3", 162, 192, 170, 206), n("12:22 PM · 3 min", 102, 215, 213, 232),
        n("No bargaining, doorstep pick-up", 102, 242, 306, 257),
        n("₹49.40", 298, 190, 355, 208), n("₹52.00", 312, 215, 355, 229),
        n("Auto Priority", 102, 292, 207, 310), n("3", 226, 294, 234, 308), n("12:21 PM · 2 min", 102, 317, 213, 334),
        n("Priority Matching, Faster Pick-up", 102, 344, 309, 359),
        n("₹76.00", 298, 292, 355, 310), n("₹80.00", 312, 317, 355, 331),
        n("Premier AC", 102, 395, 195, 413), n("4", 214, 397, 222, 411), n("12:24 PM · 5 min", 102, 420, 213, 437),
        n("Good deal", 120, 448, 175, 462), n("₹64.98", 298, 395, 355, 413), n("₹144.98", 306, 420, 355, 434),
        n("Uber Go AC", 102, 500, 197, 518), n("4", 216, 502, 224, 516), n("12:24 PM · 6 min", 102, 525, 213, 542),
        n("Affordable compact AC rides", 102, 552, 284, 567),
        n("₹118.75", 292, 500, 355, 518), n("₹125.00", 310, 525, 355, 539),
        n("Economy", 15, 600, 93, 615),
        n("Insure your trips up to ₹10L", 66, 652, 243, 668), n("Cash", 62, 719, 99, 735),
        n("Choose Auto", 140, 773, 246, 791),
    )

    @Test fun uber_fare_list() {
        assertEquals(ScreenState.FARE_LIST, FareReader.state(uberFareList, uber))
        assertEquals(
            mapOf(
                "uber_auto" to (49.40 to 3), "uber_auto_priority" to (76.00 to 2),
                "uber_premier_ac" to (64.98 to 5), "uber_go_ac" to (118.75 to 6),
            ),
            fares(FareReader.parse(uberFareList, uber)),
        )
    }

    @Test fun uber_row_exposed_as_one_description() {
        // Android apps often merge a row into one content description; Mode A must read it.
        val merged = listOf(
            n("Choose a ride", 130, 222, 255, 242),
            ScreenNode(null, "Auto Priority, 3 seats, 12:21 PM · 2 min, ₹76.00, ₹80.00", 15, 290, 370, 370),
            ScreenNode(null, "Auto, 3 seats, 12:22 PM · 3 min, ₹49.40, ₹52.00, Cheaper", 15, 270, 370, 372),
            ScreenNode(null, "Go Rentals, 4 min away, ₹400.00, 1 hr/10 km", 15, 380, 370, 460),
        )
        assertEquals(
            mapOf("uber_auto" to (49.40 to 3), "uber_auto_priority" to (76.00 to 2)),
            fares(FareReader.parse(merged, uber)),
        ) // rentals excluded
    }

    @Test fun uber_loading_and_logged_out() {
        val gathering = listOf(n("Mesa School of Business", 132, 67, 270, 80), n("Gathering options", 498, 352, 662, 372))
        assertEquals(ScreenState.LOADING, FareReader.state(gathering, uber))
        val login = listOf(
            n("Go anywhere with Uber", 15, 327, 271, 352), n("Continue with Google", 127, 501, 285, 521),
            n("Continue with Mobile", 130, 557, 283, 577), n("Find my account", 146, 709, 266, 729),
        )
        assertEquals(ScreenState.LOGGED_OUT, FareReader.state(login, uber))
    }

    // ---------- Rapido: fare list at 00:26.4 of the Rapido recording, plus a repeated section ----------
    private val rapidoFareList = listOf(
        n("All", 23, 165, 43, 180), n("Women Preferred", 72, 165, 162, 180),
        n("Priority Services", 194, 165, 280, 180), n("Extra Space", 312, 165, 372, 180),
        n("Bike Direct", 84, 227, 151, 243), n("3 min away • Drop 12:24 pm • 1", 84, 244, 250, 256), n("₹40", 346, 234, 371, 250),
        n("Bike Rated by Women", 84, 277, 218, 293), n("Captains Rated by Women", 88, 296, 224, 310),
        n("3 min away • Drop 12:24 pm • 1", 84, 313, 250, 325), n("₹40", 346, 294, 371, 310),
        n("Scooty Direct", 84, 347, 167, 363), n("3 min away • Drop 12:24 pm • 1", 84, 364, 250, 376), n("₹44", 346, 354, 371, 370),
        n("Auto Lite", 84, 407, 154, 423), n("3 min away • Drop 12:25 pm • 3", 84, 424, 250, 436), n("₹49", 346, 414, 371, 430),
        n("Auto", 92, 479, 121, 495), n("Hassle-free Auto rides", 92, 496, 198, 508),
        n("3 min away • Drop 12:25 pm • 3", 92, 509, 262, 521), n("₹49", 333, 492, 359, 508),
        n("Auto Priority", 84, 564, 162, 580), n("Quickest", 172, 565, 222, 579),
        n("3 min away • Drop 12:25 pm • 3", 84, 581, 250, 593), n("₹100", 338, 571, 371, 587),
        n("Cab Non AC", 84, 624, 158, 640), n("3 min away • Drop 12:25 pm • 4", 84, 641, 250, 653), n("₹101", 338, 631, 371, 647),
        n("Priority Services", 15, 690, 116, 706),
        n("Auto Priority", 84, 740, 162, 756), n("3 min away • Drop 12:25 pm • 3", 84, 757, 250, 769), n("₹100", 338, 747, 371, 763),
        n("Pet Friendly", 15, 790, 88, 806),
        n("Auto Pet", 84, 830, 136, 846), n("3 min away • Drop 12:25 pm • 3", 84, 847, 250, 859), n("₹104", 338, 837, 371, 853),
        n("Cash", 42, 728, 75, 744), n("Promos", 63, 774, 113, 786), n("Bargain & Book", 40, 796, 135, 812),
        n("Book now", 239, 796, 300, 812),
    )

    @Test fun rapido_fare_list_dedupes_sections_and_skips_specialty() {
        assertEquals(ScreenState.FARE_LIST, FareReader.state(rapidoFareList, rapido))
        assertEquals(
            mapOf(
                "rapido_bike_direct" to (40.0 to 3), "rapido_scooty_direct" to (44.0 to 3),
                "rapido_auto_lite" to (49.0 to 3), "rapido_auto" to (49.0 to 3),
                "rapido_auto_priority" to (100.0 to 3), "rapido_cab_non_ac" to (101.0 to 3),
            ),
            fares(FareReader.parse(rapidoFareList, rapido)),
        ) // "Bike Rated by Women" and "Auto Pet" excluded; repeated "Auto Priority" counted once
    }

    @Test fun rapido_home_is_not_a_fare_list() {
        val home = listOf(
            n("Where do you want to go?", 68, 95, 252, 110), n("Explore", 15, 620, 68, 635),
            n("Bike Direct", 24, 713, 82, 727), n("Auto", 134, 713, 158, 727),
            n("Cab Non AC", 207, 713, 271, 727), n("Get up to ₹100 OFF", 30, 765, 230, 790),
        )
        assertEquals(ScreenState.UNKNOWN, FareReader.state(home, rapido))
    }

    @Test fun rapido_logged_out_and_ride_in_progress() {
        val login = listOf(n("What's your number?", 403, 128, 585, 146), n("Enter Mobile Number", 477, 207, 630, 223))
        assertEquals(ScreenState.LOGGED_OUT, FareReader.state(login, rapido))
        val searching = listOf(n("Searching in progress", 19, 199, 186, 216), n("Total Fare", 480, 303, 537, 316))
        assertEquals(ScreenState.RIDE_IN_PROGRESS, FareReader.state(searching, rapido))
    }

    @Test fun price_pattern_edge_cases() {
        assertEquals(49.40 to 49.40, FareReader.firstPrice("₹49.40", uber))
        assertEquals(118.0 to 131.0, FareReader.firstPrice("₹118 - ₹131", ola))
        assertEquals(1458.65 to 1458.65, FareReader.firstPrice("₹1,458.65", uber))
        assertEquals(null, FareReader.firstPrice("Insure your trips up to ₹10L", uber))
        assertEquals(49.0 to 49.0, FareReader.firstPrice("Rs. 49", uber))
    }
}
