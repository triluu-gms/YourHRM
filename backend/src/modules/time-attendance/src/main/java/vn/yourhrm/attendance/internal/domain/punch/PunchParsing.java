package vn.yourhrm.attendance.internal.domain.punch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure rules for one punch line of an import file (BR-ATT-020/025): date / time parsing, direction and verify-method
 * codes, normalisation of the device's wall-clock time to UTC in the device's zone (NFR-ATT-005) and the
 * deterministic dedup key (NFR-ATT-001: re-importing the same file creates no duplicate).
 *
 * <p>Dates follow Core's import engine (VN order {@code dd/MM/yyyy}, also ISO {@code yyyy-MM-dd} and
 * {@code yyyy/MM/dd}); a US-ordered export ({@code MM/dd}) is not detected and must be exported in VN or ISO order.
 */
public final class PunchParsing {

    private PunchParsing() {
    }

    /** A punch as stored: {@code workDateHint} is the local calendar date of {@code localTs} (partition key). */
    public record Normalised(Instant punchAt, LocalDateTime localTs, String tzName, LocalDate workDateHint,
            String dedupKey) {
    }

    public static final Set<String> DIRECTIONS = Set.of("IN", "OUT", "UNKNOWN");
    public static final Set<String> VERIFY_METHODS = Set.of("FINGERPRINT", "FACE", "CARD", "PASSWORD", "UNKNOWN");

    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private static final List<DateTimeFormatter> TIMES = List.of(
            DateTimeFormatter.ofPattern("H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mm[:ss][ ]a")
                    .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.SMART));

    // ------------------------------------------------------------------------------------------------- date/time

    /** A date of a punch file. */
    public static LocalDate date(String raw) {
        String v = raw == null ? "" : raw.strip();
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // next pattern
            }
        }
        throw new DateTimeParseException("Not a date", v, 0);
    }

    /** A time of day {@code H:mm[:ss]} or {@code h:mm[:ss] AM/PM}; fractions of a second are dropped. */
    public static LocalTime time(String raw) {
        String v = raw == null ? "" : raw.strip();
        int dot = v.indexOf('.');
        if (dot > 0 && v.indexOf(':') > 0 && dot > v.lastIndexOf(':')) {
            v = v.substring(0, dot);
        }
        for (DateTimeFormatter f : TIMES) {
            try {
                return LocalTime.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // next pattern
            }
        }
        throw new DateTimeParseException("Not a time", v, 0);
    }

    /** A date and time in one cell ({@code date time}, also ISO {@code dateTtime}). */
    public static LocalDateTime dateTime(String raw) {
        String v = raw == null ? "" : raw.strip().replace('T', ' ').replaceAll("\\s+", " ");
        int sp = v.indexOf(' ');
        if (sp < 0) {
            throw new DateTimeParseException("Not a date and time", v, 0);
        }
        return LocalDateTime.of(date(v.substring(0, sp)), time(v.substring(sp + 1)));
    }

    // ------------------------------------------------------------------------------------------------------- codes

    private static final Set<String> IN = Set.of("IN", "I", "0", "3", "4", "CIN", "CHECKIN", "BREAKIN", "OTIN", "VAO",
            "GIOVAO", "DUTYON", "ON");
    private static final Set<String> OUT = Set.of("OUT", "O", "1", "2", "5", "COUT", "CHECKOUT", "BREAKOUT", "OTOUT",
            "RA", "GIORA", "DUTYOFF", "OFF");

    /**
     * IN / OUT / UNKNOWN from the codes of the standard template and the device exports (ZKTeco state 0..5,
     * "C/In", "Check-out", "Vào", "Ra" ...); blank = UNKNOWN; {@code null} = not recognised.
     */
    public static String direction(String raw) {
        String k = token(raw);
        if (k.isEmpty() || "UNKNOWN".equals(k)) {
            return "UNKNOWN";
        }
        if (IN.contains(k)) {
            return "IN";
        }
        if (OUT.contains(k)) {
            return "OUT";
        }
        return null;
    }

    /**
     * FINGERPRINT / FACE / CARD / PASSWORD / UNKNOWN; blank = null (not given). Only the method's name is kept: no
     * biometric template is ever stored (NFR-ATT-008).
     */
    public static String verifyMethod(String raw) {
        String k = token(raw);
        if (k.isEmpty()) {
            return null;
        }
        return switch (k) {
            case "FINGERPRINT", "FINGER", "FP", "VANTAY", "1" -> "FINGERPRINT";
            case "FACE", "KHUONMAT", "15" -> "FACE";
            case "CARD", "THE", "RFID", "2", "4" -> "CARD";
            case "PASSWORD", "PWD", "PW", "MATKHAU", "0" -> "PASSWORD";
            default -> "UNKNOWN";
        };
    }

    /** Upper-case, accents removed, only letters and digits ("C/In" → "CIN", "Vào" → "VAO"). */
    static String token(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    // --------------------------------------------------------------------------------------------- normalisation

    /**
     * The device's wall-clock {@code local} (seconds kept, fractions dropped) in {@code zone} as UTC. In a DST gap or
     * overlap the earlier valid offset is used (no VN zone has DST; deterministic for other zones).
     */
    public static Normalised normalise(String terminalCode, String enrolId, LocalDateTime local, ZoneId zone) {
        LocalDateTime ts = local.truncatedTo(ChronoUnit.SECONDS);
        Instant at = ZonedDateTime.ofLocal(ts, zone, null).toInstant();
        return new Normalised(at, ts, zone.getId(), ts.toLocalDate(), dedupKey(terminalCode, enrolId, at));
    }

    /** {@code hex sha256(terminal_code|enrol_id|punch_at)} with {@code punch_at} as ISO-8601 UTC (DDL comment). */
    public static String dedupKey(String terminalCode, String enrolId, Instant punchAt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((terminalCode + "|" + enrolId + "|" + punchAt.truncatedTo(ChronoUnit.SECONDS))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A valid IANA zone id, else null. */
    public static ZoneId zone(String id) {
        try {
            return id == null ? null : ZoneId.of(id.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
