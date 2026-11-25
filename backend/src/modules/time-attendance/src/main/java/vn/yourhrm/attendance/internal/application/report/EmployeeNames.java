package vn.yourhrm.attendance.internal.application.report;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.EmployeeDirectory;

/**
 * Employee number and (masked) full name of the employments of a report, from People's {@link EmployeeDirectory}
 * with the caller's own rights: an employment outside the caller's {@code hr.employee.view} scope, or People not
 * deployed, simply has no name (the report still shows its employment id). One directory read per employment, so
 * callers pass one page at a time.
 */
@Component
class EmployeeNames {

    record Name(String employeeNo, String fullName) {
        static final Name NONE = new Name(null, null);
    }

    private final ObjectProvider<EmployeeDirectory> directory;

    EmployeeNames(ObjectProvider<EmployeeDirectory> directory) {
        this.directory = directory;
    }

    Map<UUID, Name> of(Collection<UUID> employmentIds, LocalDate asOf) {
        Map<UUID, Name> out = new LinkedHashMap<>();
        EmployeeDirectory dir = directory.getIfAvailable();
        for (UUID e : employmentIds) {
            if (e == null || out.containsKey(e)) {
                continue;
            }
            Name n = Name.NONE;
            if (dir != null) {
                n = dir.snapshot(e, asOf, null)
                        .map(s -> new Name(s.employment() == null ? null : s.employment().employeeNo(),
                                s.person() == null ? null : s.person().fullName()))
                        .orElse(Name.NONE);
            }
            out.put(e, n);
        }
        return out;
    }
}
