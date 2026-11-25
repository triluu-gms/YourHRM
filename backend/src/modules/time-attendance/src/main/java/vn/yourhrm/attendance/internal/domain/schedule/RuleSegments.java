package vn.yourhrm.attendance.internal.domain.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Rule-based schedule assignment (BR-ATT-015, US-ATT-010): for one assignment line (all its versions), the
 * segments that the schedule rules produce. The first ACTIVE rule by {@code priority} (then code) whose criteria match
 * the version's attributes and whose validity covers the day wins; a {@code null} criterion matches anything.
 * Manually assigned ranges (MANUAL / IMPORT / OPENING_LOAD rows) override rules and are cut out. Pure and
 * deterministic: the same inputs give the same segments, so regeneration is idempotent.
 *
 * <p>Org unit criteria match the version's org unit exactly; the sub-tree match of the DDL comment needs Core's org
 * tree, which no published API provides yet.
 */
public final class RuleSegments {

    /** The attributes of one assignment version, from People ({@code att.ext_hr_assignment}). */
    public record Version(DateRange validity, UUID legalEntityId, UUID orgUnitId, UUID locationId, UUID jobTitleId,
            String employeeCategoryCode, String workingRegimeCode) {
    }

    public record Rule(UUID id, String code, int priority, UUID legalEntityId, UUID orgUnitId, UUID locationId,
            UUID jobTitleId, String employeeCategoryCode, String workingRegimeCode, UUID patternId,
            DateRange validity) {

        boolean matches(Version v) {
            return eq(legalEntityId, v.legalEntityId()) && eq(orgUnitId, v.orgUnitId())
                    && eq(locationId, v.locationId()) && eq(jobTitleId, v.jobTitleId())
                    && eq(employeeCategoryCode, v.employeeCategoryCode())
                    && eq(workingRegimeCode, v.workingRegimeCode());
        }

        private static boolean eq(Object criterion, Object value) {
            return criterion == null || criterion.equals(value);
        }
    }

    /** A generated segment ({@code source = RULE}). */
    public record Segment(UUID ruleId, UUID patternId, DateRange validity) {
    }

    private RuleSegments() {
    }

    public static List<Segment> generate(List<Version> versions, List<Rule> rules, List<DateRange> manual) {
        List<Rule> ordered = rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority).thenComparing(Rule::code)).toList();
        List<Segment> raw = new ArrayList<>();
        for (Version v : versions.stream().sorted(Comparator.comparing(x -> x.validity().from())).toList()) {
            TreeSet<LocalDate> cuts = new TreeSet<>();
            cuts.add(v.validity().from());
            for (Rule r : ordered) {
                addInside(cuts, v.validity(), r.validity().from());
                if (r.validity().to() != null) {
                    addInside(cuts, v.validity(), r.validity().to());
                }
            }
            List<LocalDate> starts = new ArrayList<>(cuts);
            for (int i = 0; i < starts.size(); i++) {
                LocalDate start = starts.get(i);
                LocalDate end = i + 1 < starts.size() ? starts.get(i + 1) : v.validity().to();
                ordered.stream().filter(r -> r.validity().contains(start) && r.matches(v)).findFirst()
                        .ifPresent(r -> raw.add(new Segment(r.id(), r.patternId(), new DateRange(start, end))));
            }
        }
        List<Segment> merged = merge(raw);
        List<Segment> out = new ArrayList<>();
        for (Segment s : merged) {
            for (DateRange piece : s.validity().minus(manual)) {
                out.add(new Segment(s.ruleId(), s.patternId(), piece));
            }
        }
        return out;
    }

    private static void addInside(TreeSet<LocalDate> cuts, DateRange range, LocalDate d) {
        if (range.contains(d)) {
            cuts.add(d);
        }
    }

    /** Joins adjacent segments of the same rule (also across version boundaries). */
    private static List<Segment> merge(List<Segment> raw) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : raw) {
            if (!out.isEmpty()) {
                Segment last = out.getLast();
                if (last.ruleId().equals(s.ruleId()) && Objects.equals(last.validity().to(), s.validity().from())) {
                    out.set(out.size() - 1, new Segment(last.ruleId(), last.patternId(),
                            new DateRange(last.validity().from(), s.validity().to())));
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }
}
