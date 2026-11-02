package app.hail.android

import org.junit.Assert.assertEquals
import org.junit.Test

class RideCategoryTest {
    @Test fun words_map_to_categories() {
        val cases = mapOf(
            "auto" to RideCategory.AUTO, "rickshaw please" to RideCategory.AUTO,
            "bike" to RideCategory.BIKE, "scooty" to RideCategory.BIKE,
            "cab" to RideCategory.CAB, "car" to RideCategory.CAB, "Cab" to RideCategory.CAB,
            "cab XL" to RideCategory.CAB_XL, "big cab" to RideCategory.CAB_XL,
            "SUV" to RideCategory.CAB_XL, "sedan" to RideCategory.PREMIUM,
            "premium cab" to RideCategory.PREMIUM, "uber black" to RideCategory.PREMIUM,
            "prime suv" to RideCategory.CAB_XL, "badi gaadi" to RideCategory.CAB_XL, "6 seater" to RideCategory.CAB_XL,
        )
        cases.forEach { (said, want) -> assertEquals(said, want, RideCategory.detect(said)) }
        assertEquals(null, RideCategory.detect("hmm not sure"))
    }
}
