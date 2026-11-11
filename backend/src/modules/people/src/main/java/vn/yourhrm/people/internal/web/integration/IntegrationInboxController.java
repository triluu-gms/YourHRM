package vn.yourhrm.people.internal.web.integration;

import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.integration.InboundEventService;
import vn.yourhrm.people.internal.infra.integration.HrInbox;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Operations view of People's inbox (US-PPL-082 "dead-letter visible to operations"): the events other modules sent
 * to People with their processing status; {@code status=FAILED} lists the dead letters with attempts and the last
 * error (no payloads are stored). Permission: {@code hr.import.run} (closest existing operations permission; a
 * dedicated {@code hr.integration.view} is requested in the stream report).
 */
@RestController
@RequestMapping("/api/v1/people/integration")
class IntegrationInboxController {

    private static final Set<String> STATUSES = Set.of("RECEIVED", "PROCESSED", "FAILED", "SKIPPED");

    private final InboundEventService inbound;

    IntegrationInboxController(InboundEventService inbound) {
        this.inbound = inbound;
    }

    @GetMapping("/inbox")
    @RequiresPermission(PeoplePermissions.IMPORT_RUN)
    public List<HrInbox.Message> inbox(@RequestParam(required = false) String status,
            @RequestParam(required = false) String consumer, @RequestParam(defaultValue = "100") int limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.unprocessable("invalid-status", "status must be one of " + STATUSES);
        }
        return inbound.messages(status, consumer, limit);
    }
}
