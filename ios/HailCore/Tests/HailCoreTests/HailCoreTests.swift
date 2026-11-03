import Foundation
import XCTest
@testable import HailCore

/// Fixtures transcribed from the founder's iPhone screen recordings (Bangalore, 2026-11-02), i.e.
/// the very screens the iPhone app reads through the screen broadcast. Same as the Android tests.
final class FareReaderTests: XCTestCase {
    static let configs: [String: AppReaderConfig] = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("backend/app/readers_config.json")
        return try! ReaderConfigs.parse(try! Data(contentsOf: url))
    }()
    var ola: AppReaderConfig { Self.configs["ola"]! }
    var uber: AppReaderConfig { Self.configs["uber"]! }
    var rapido: AppReaderConfig { Self.configs["rapido"]! }

    func n(_ t: String, _ l: Int, _ top: Int, _ r: Int, _ b: Int) -> ScreenNode {
        ScreenNode(t, left: l, top: top, right: r, bottom: b)
    }
    func dict(_ f: [Fare]) -> [String: Double] { Dictionary(uniqueKeysWithValues: f.map { ($0.productKey, $0.fareMin) }) }
    func etas(_ f: [Fare]) -> [String: Int?] { Dictionary(uniqueKeysWithValues: f.map { ($0.productKey, $0.etaMinutes) }) }

    lazy var olaFareList = [
        n("38/2, Esteem Enclave, Sarvobhogam N...", 47, 165, 305, 182),
        n("Book Any", 102, 270, 172, 290), n("Mini, Prime Sedan, Prime Pl...", 102, 294, 262, 308),
        n("₹118 - ₹131", 278, 270, 360, 290), n("₹130 - ₹145", 290, 294, 360, 308), n("3 min", 36, 298, 68, 314),
        n("Auto", 102, 345, 137, 365), n("Quickest auto ride in town", 102, 370, 247, 384),
        n("₹46", 330, 345, 360, 365), n("₹51", 338, 370, 360, 384), n("1 min", 36, 370, 64, 384),
        n("Bike", 102, 420, 134, 440), n("₹40", 330, 420, 360, 440), n("1 min", 36, 445, 64, 459),
        n("Priority", 102, 495, 157, 515), n("₹124", 324, 495, 360, 515), n("₹137", 332, 520, 360, 534), n("1 min", 36, 520, 64, 534),
        n("Mini", 102, 570, 133, 590), n("₹118", 324, 570, 360, 590), n("₹130", 332, 595, 360, 609), n("4 min", 36, 595, 66, 609),
        n("Prime Sedan", 102, 645, 196, 665), n("₹124", 324, 645, 360, 665), n("₹137", 332, 670, 360, 684), n("3 min", 36, 670, 66, 684),
        n("Cash", 75, 735, 107, 751), n("Book Any", 158, 790, 228, 810),
    ]

    func testOlaFareList() {
        XCTAssertEqual(FareReader.state(olaFareList, ola), .fareList)
        let f = FareReader.parse(olaFareList, ola)
        XCTAssertEqual(dict(f), ["ola_auto": 46, "ola_bike": 40, "ola_priority": 124, "ola_mini": 118, "ola_prime_sedan": 124])
        XCTAssertEqual(etas(f)["ola_mini"]!, 4)
    }

    func testUberFareList() {
        let s = [
            n("Rides we think you'll like", 15, 145, 215, 162),
            n("Auto", 102, 190, 142, 208), n("12:22 PM · 3 min", 102, 215, 213, 232),
            n("₹49.40", 298, 190, 355, 208), n("₹52.00", 312, 215, 355, 229),
            n("Premier AC", 102, 395, 195, 413), n("12:24 PM · 5 min", 102, 420, 213, 437),
            n("₹64.98", 298, 395, 355, 413), n("₹144.98", 306, 420, 355, 434),
            n("Uber Go AC", 102, 500, 197, 518), n("12:24 PM · 6 min", 102, 525, 213, 542),
            n("₹118.75", 292, 500, 355, 518), n("₹125.00", 310, 525, 355, 539),
            n("Insure your trips up to ₹10L", 66, 652, 243, 668),
        ]
        XCTAssertEqual(FareReader.state(s, uber), .fareList)
        let f = FareReader.parse(s, uber)
        XCTAssertEqual(dict(f), ["uber_auto": 49.40, "uber_premier_ac": 64.98, "uber_go_ac": 118.75])
        XCTAssertEqual(etas(f)["uber_go_ac"]!, 6)
    }

    func testRapidoDedupesAndSkipsSpecialty() {
        let s = [
            n("Women Preferred", 72, 165, 162, 180),
            n("Bike Direct", 84, 227, 151, 243), n("3 min away • Drop 12:24 pm • 1", 84, 244, 250, 256), n("₹40", 346, 234, 371, 250),
            n("Bike Rated by Women", 84, 277, 218, 293), n("₹40", 346, 294, 371, 310),
            n("Auto", 92, 479, 121, 495), n("3 min away • Drop 12:25 pm • 3", 92, 509, 262, 521), n("₹49", 333, 492, 359, 508),
            n("Auto Priority", 84, 564, 162, 580), n("₹100", 338, 571, 371, 587),
            n("Auto Priority", 84, 740, 162, 756), n("₹100", 338, 747, 371, 763),
            n("Auto Pet", 84, 830, 136, 846), n("₹104", 338, 837, 371, 853),
            n("Book now", 239, 796, 300, 812),
        ]
        XCTAssertEqual(FareReader.state(s, rapido), .fareList)
        XCTAssertEqual(dict(FareReader.parse(s, rapido)),
                       ["rapido_bike_direct": 40, "rapido_auto": 49, "rapido_auto_priority": 100])
    }

    func testMergedRowStringIsRead() {
        // OCR can merge a row into one line.
        let s = [n("Choose a ride", 130, 222, 255, 242), n("Auto 3 12:22 PM · 3 min ₹49.40 ₹52.00", 15, 270, 370, 300)]
        XCTAssertEqual(dict(FareReader.parse(s, uber)), ["uber_auto": 49.40])
    }

    func testScreenStates() {
        XCTAssertEqual(FareReader.state([n("Go anywhere with Uber", 15, 327, 271, 352), n("Continue with Mobile", 130, 557, 283, 577)], uber), .loggedOut)
        XCTAssertEqual(FareReader.state([n("Gathering options", 98, 352, 262, 372)], uber), .loading)
        XCTAssertEqual(FareReader.state([n("What's your number?", 3, 128, 185, 146)], rapido), .loggedOut)
        XCTAssertEqual(FareReader.state([n("Where do you want to go?", 68, 95, 252, 110), n("Auto", 134, 713, 158, 727)], rapido), .unknown)
    }

    func testPricePattern() {
        XCTAssertEqual(FareReader.firstPrice("₹1,458.65", uber)!.0, 1458.65)
        XCTAssertNil(FareReader.firstPrice("Insure your trips up to ₹10L", uber))
        XCTAssertEqual(FareReader.firstPrice("₹118 - ₹131", ola)!.1, 131)
    }

    func testVisionBoxConversion() {
        // Vision: normalised, origin bottom-left. A box at the top-left quarter of a 1000x2000 screen.
        let node = ScreenNode.fromVision("Auto", x: 0.1, y: 0.75, w: 0.2, h: 0.05, width: 1000, height: 2000)
        XCTAssertEqual([node.left, node.top, node.right, node.bottom], [100, 400, 300, 500])
    }

    func testAccumulatorAndObservations() {
        let acc = FareAccumulator()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        let a = Fare(productKey: "ola_auto", rideType: "auto", fareMin: 46, fareMax: 46, etaMinutes: 1)
        XCTAssertTrue(acc.add([a], at: t0))
        XCTAssertFalse(acc.add([a], at: t0.addingTimeInterval(1)))
        XCTAssertFalse(acc.isStable(at: t0.addingTimeInterval(1)))
        XCTAssertTrue(acc.isStable(at: t0.addingTimeInterval(2)))
        let bike = Fare(productKey: "ola_bike", rideType: "bike", fareMin: 40, fareMax: 40, etaMinutes: nil)
        acc.add([bike], at: t0.addingTimeInterval(3)) // scrolled: new row
        XCTAssertEqual(acc.fares.count, 2)
        let obs = Observations.json(app: "ola", fares: acc.fares, capturedAt: t0, confidence: 0.9, rideTypes: ["auto"])
        XCTAssertEqual(obs.count, 1)
        XCTAssertEqual(obs[0]["product"] as? String, "ola_auto")
        XCTAssertEqual(obs[0]["screen_signature"] as? String, "ola.ios_ocr.draft1")
    }
}

final class RideCategoryAndVoiceTests: XCTestCase {
    func testCategories() {
        let cases: [String: RideCategory] = ["auto": .auto, "rickshaw please": .auto, "scooty": .bike, "car": .cab,
                                             "sedan": .premium, "uber black": .premium, "big cab": .cab_xl,
                                             "prime suv": .cab_xl, "badi gaadi": .cab_xl]
        for (said, want) in cases { XCTAssertEqual(RideCategory.detect(said), want, said) }
        XCTAssertNil(RideCategory.detect("hmm not sure"))
    }

    let card = [
        SpokenOption(id: "opt_12", app: "ola", product: "Auto", rideType: "auto"),
        SpokenOption(id: "opt_24", app: "rapido", product: "Auto", rideType: "auto"),
        SpokenOption(id: "opt_1", app: "uber", product: "Auto", rideType: "auto"),
        SpokenOption(id: "opt_8", app: "uber", product: "Go Non AC", rideType: "cab"),
        SpokenOption(id: "opt_4", app: "uber", product: "Uber Go AC", rideType: "cab"),
    ]

    func testVoicePick() {
        func p(_ s: String) -> Pick { VoicePick.match(s, card, recommendedId: "opt_12") }
        XCTAssertEqual(p("Ola auto"), .choose("opt_12"))
        XCTAssertEqual(p("uber wala auto"), .choose("opt_1"))
        XCTAssertEqual(p("Uber Go AC"), .choose("opt_4"))
        XCTAssertEqual(p("doosra"), .choose("opt_24"))
        XCTAssertEqual(p("haan"), .choose("opt_12"))
        XCTAssertEqual(p("nahi"), .decline)
        XCTAssertEqual(p("ola prime suv"), .unclear)
        XCTAssertEqual(p("hmm"), .unclear)
    }
}
