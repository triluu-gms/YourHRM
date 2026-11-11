package vn.yourhrm.people.internal.web.privacy;

import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PrivacyRequests;
import vn.yourhrm.people.api.PrivacyRequests.AnonymisationConfirmation;
import vn.yourhrm.people.api.PrivacyRequests.ExportPackage;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * HR / DPO triggers of People's DSR handlers (US-PPL-088, BR-PPL-153) until the SaaS DSR orchestration calls
 * {@link PrivacyRequests} in-process: the export package and the anonymisation (with an explicit confirmation).
 */
@RestController
@RequestMapping("/api/v1/people/privacy/persons/{personId}")
class PrivacyController {

    record ExportBody(@Size(max = 100) String requestRef) {
    }

    record AnonymiseBody(@Size(max = 100) String requestRef, boolean confirm) {
    }

    private final PrivacyRequests privacy;

    PrivacyController(PrivacyRequests privacy) {
        this.privacy = privacy;
    }

    @PostMapping("/export")
    @RequiresPermission(PeoplePermissions.DSR_EXECUTE)
    public ExportPackage export(@PathVariable UUID personId, @RequestBody(required = false) ExportBody body) {
        return privacy.exportPersonData(personId, body == null ? null : body.requestRef());
    }

    @PostMapping("/anonymise")
    @RequiresPermission(PeoplePermissions.DSR_EXECUTE)
    public AnonymisationConfirmation anonymise(@PathVariable UUID personId, @RequestBody AnonymiseBody body) {
        if (!body.confirm()) {
            throw ApiException.unprocessable("confirmation-required",
                    "Anonymisation is irreversible: send confirm = true");
        }
        return privacy.anonymise(personId, body.requestRef());
    }
}
