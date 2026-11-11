package vn.yourhrm.people.internal.web.ess;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.ess.EssService;
import vn.yourhrm.people.internal.application.ess.EssService.Document;
import vn.yourhrm.people.internal.application.ess.EssService.MyEmployment;
import vn.yourhrm.people.internal.application.ess.EssService.MyProfile;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * People ESS pages (EP-PPL-10): "my profile" (US-PPL-072), "my employment" (US-PPL-073) and "my documents" with signed
 * download links (US-PPL-073 / US-PPL-077 former employees). Read-only; the employee is always the caller.
 */
@RestController
@RequestMapping("/api/v1/people/me")
class EssProfileController {

    private final EssService ess;

    EssProfileController(EssService ess) {
        this.ess = ess;
    }

    @GetMapping("/profile")
    @RequiresPermission(PeoplePermissions.ESS_VIEW)
    public MyProfile profile() {
        return ess.profile();
    }

    @GetMapping("/employments")
    @RequiresPermission(PeoplePermissions.ESS_VIEW)
    public List<MyEmployment> employments() {
        return ess.employment();
    }

    @GetMapping("/documents")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public List<Document> documents() {
        return ess.documents();
    }

    @GetMapping("/documents/{documentId}/download-link")
    @RequiresPermission(PeoplePermissions.CONTRACT_VIEW)
    public Documents.DownloadLink downloadLink(@PathVariable UUID documentId) {
        return ess.downloadLink(documentId);
    }
}
