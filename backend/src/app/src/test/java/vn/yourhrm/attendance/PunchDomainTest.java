package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.punch.PunchMatching;
import vn.yourhrm.attendance.internal.domain.punch.PunchMatching.Span;
import vn.yourhrm.attendance.internal.domain.punch.PunchParsing;

/** Pure punch rules (BR-ATT-020/022/025): parsing, codes, normalisation, dedup key, enrolment matching. */
class PunchDomainTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void datesAndTimesOfTheFileFormatsAreRead() {
        LocalDateTime expected = LocalDateTime.of(2026, 11, 3, 7, 58, 12);
        assertThat(PunchParsing.dateTime("03/11/2026 07:58:12")).isEqualTo(expected);
        assertThat(PunchParsing.dateTime("2026-11-03T07:58:12")).isEqualTo(expected);
        assertThat(PunchParsing.dateTime("2026/11/3 7:58:12")).isEqualTo(expected);
        assertThat(PunchParsing.dateTime("3/11/2026  7:58:12.345")).isEqualTo(expected);
        assertThat(PunchParsing.dateTime("03-11-2026 07:58:12 AM")).isEqualTo(expected);
        assertThat(PunchParsing.time("5:05 PM").toString()).isEqualTo("17:05");
        assertThatThrownBy(() -> PunchParsing.dateTime("31/02/2026 08:00")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> PunchParsing.time("25:00")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void directionsAndVerifyMethodsOfTheVendorsAreMapped() {
        assertThat(List.of("VÀO", "in", "C/In", "Check-in", "0", "OT-In", "Break In").stream()
                .map(PunchParsing::direction)).containsOnly("IN");
        assertThat(List.of("Ra", "OUT", "C/Out", "Check-out", "1", "Break-Out").stream()
                .map(PunchParsing::direction)).containsOnly("OUT");
        assertThat(PunchParsing.direction("")).isEqualTo("UNKNOWN");
        assertThat(PunchParsing.direction("sideways")).isNull();
        assertThat(PunchParsing.verifyMethod("Vân tay")).isEqualTo("FINGERPRINT");
        assertThat(PunchParsing.verifyMethod("Face")).isEqualTo("FACE");
        assertThat(PunchParsing.verifyMethod("palm")).isEqualTo("UNKNOWN");
        assertThat(PunchParsing.verifyMethod(" ")).isNull();
    }

    @Test
    void theWallClockIsNormalisedInTheDeviceZoneAndTheDedupKeyIsDeterministic() {
        var n = PunchParsing.normalise("T01", "101", LocalDateTime.of(2026, 11, 3, 0, 30, 5, 900_000_000), VN);
        assertThat(n.punchAt()).isEqualTo(Instant.parse("2026-11-02T17:30:05Z"));
        assertThat(n.workDateHint()).isEqualTo(LocalDate.of(2026, 11, 3)); // local date, not the UTC date
        assertThat(n.tzName()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(n.dedupKey()).hasSize(64)
                .isEqualTo(PunchParsing.normalise("T01", "101", LocalDateTime.of(2026, 11, 3, 0, 30, 5), VN).dedupKey())
                .isNotEqualTo(PunchParsing.normalise("T02", "101", LocalDateTime.of(2026, 11, 3, 0, 30, 5), VN)
                        .dedupKey());
    }

    @Test
    void theDeviceMappingWinsOverTheTenantWideOneAndGapsAreExpired() {
        UUID dev = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LocalDate jan = LocalDate.of(2027, 1, 1);
        List<Span> spans = List.of(
                new Span(UUID.randomUUID(), null, "101", a, jan, null),
                new Span(UUID.randomUUID(), dev, "101", b, jan, jan.plusMonths(1)),
                new Span(UUID.randomUUID(), other, "202", a, jan, null));
        assertThat(PunchMatching.decide(dev, "101", spans, jan.plusDays(3)).employmentId()).isEqualTo(b);
        assertThat(PunchMatching.decide(dev, "101", spans, jan.plusMonths(2)).employmentId()).isEqualTo(a);
        assertThat(PunchMatching.decide(dev, "101", spans, jan.minusDays(1)).status())
                .isEqualTo(PunchMatching.EXPIRED);
        assertThat(PunchMatching.decide(dev, "202", spans, jan).status()).isEqualTo(PunchMatching.UNMAPPED);
        assertThat(PunchMatching.decide(other, "202", spans, jan).valid()).isTrue();
        assertThat(PunchMatching.decide(null, "101", spans, jan).status()).isEqualTo(PunchMatching.UNKNOWN_DEVICE);
    }
}
