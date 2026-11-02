package app.hail.android

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePickTest {
    // Best-first card from the recorded Bangalore fares ("cheapest").
    private val card = listOf(
        SpokenOption("opt_13", "ola", "Bike", "bike"),
        SpokenOption("opt_21", "rapido", "Bike Direct", "bike"),
        SpokenOption("opt_5", "uber", "Bike Saver", "bike"),
        SpokenOption("opt_12", "ola", "Auto", "auto"),
        SpokenOption("opt_24", "rapido", "Auto", "auto"),
        SpokenOption("opt_1", "uber", "Auto", "auto"),
        SpokenOption("opt_3", "uber", "Premier AC", "cab"),
        SpokenOption("opt_8", "uber", "Go Non AC", "cab"),
        SpokenOption("opt_19", "ola", "Mini Non AC", "cab"),
        SpokenOption("opt_15", "ola", "Mini", "cab"),
        SpokenOption("opt_4", "uber", "Uber Go AC", "cab"),
    )
    private val best = "opt_13"
    private fun pick(s: String) = VoicePick.match(s, card, best)
    private fun id(s: String) = (pick(s) as? Pick.Choose)?.id

    @Test fun named_rides() {
        assertEquals("opt_12", id("Ola auto"))
        assertEquals("opt_1", id("uber auto please"))
        assertEquals("opt_4", id("Uber Go AC"))          // not Go Non AC
        assertEquals("opt_8", id("uber go non AC"))
        assertEquals("opt_15", id("ola mini"))            // Mini beats Mini Non AC
        assertEquals("opt_19", id("ola mini non ac"))
        assertEquals("opt_21", id("rapido bike"))
        assertEquals("opt_3", id("premier"))
        assertEquals("opt_12", id("auto"))                // best auto on the card
        assertEquals("opt_1", id("uber wala auto"))
    }

    @Test fun ordinals_and_affirmations() {
        assertEquals("opt_13", id("the first one"))
        assertEquals("opt_21", id("second"))
        assertEquals("opt_21", id("doosra wala"))
        assertEquals("opt_13", id("haan"))
        assertEquals("opt_13", id("yes book it"))
        assertEquals("opt_13", id("that one"))
    }

    @Test fun decline_and_unclear() {
        assertEquals(Pick.Decline, pick("none of these"))
        assertEquals(Pick.Decline, pick("nahi"))
        assertEquals(Pick.Decline, pick("cancel"))
        assertEquals(Pick.Unclear, pick("hmm let me think"))
        assertEquals(Pick.Unclear, pick(""))
        assertEquals(Pick.Unclear, pick("ola prime suv"))   // not on this card: ask, never guess
        assertEquals(Pick.Unclear, pick("uber black"))
    }
}
