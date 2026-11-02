package vn.yourhrm.people.internal.web.decision;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.DecisionService.Decision;
import vn.yourhrm.people.internal.application.decision.DecisionRegister;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** HR decisions (BR-PPL-130, US-PPL-057/058). Scope: the employee's (404 when out of scope). */
@RestController
@RequestMapping("/api/v1/people")
class DecisionController {

    private final DecisionRegister decisions;

    DecisionController(DecisionRegister decisions) {
        this.decisions = decisions;
    }

    record IssueRequest(@NotNull UUID employmentId, @NotBlank @Size(max = 64) String decisionTypeCode,
            @NotNull LocalDate effectiveDate, @Size(max = 200) String signatoryName,
            @Size(max = 200) String signatoryTitle) {
    }

    record ReissueRequest(LocalDate effectiveDate, @Size(max = 200) String signatoryName,
            @Size(max = 200) String signatoryTitle, @NotNull Integer version) {
    }

    record VersionRequest(@NotNull Integer version) {
    }

    @GetMapping("/employments/{employmentId}/decisions")
    @RequiresPermission(PeoplePermissions.DECISION_VIEW)
    public List<Decision> list(@PathVariable UUID employmentId) {
        return decisions.list(employmentId);
    }

    @GetMapping("/decisions/{decisionId}")
    @RequiresPermission(PeoplePermissions.DECISION_VIEW)
    public Decision get(@PathVariable UUID decisionId) {
        return decisions.get(decisionId);
    }

    @PostMapping("/decisions")
    @RequiresPermission(PeoplePermissions.DECISION_CREATE)
    public ResponseEntity<Decision> issue(@Valid @RequestBody IssueRequest req) {
        Decision d = decisions.issue(new DecisionRegister.ManualIssue(req.employmentId(), req.decisionTypeCode(),
                req.effectiveDate(), req.signatoryName(), req.signatoryTitle()));
        return ResponseEntity.created(URI.create("/api/v1/people/decisions/" + d.id())).body(d);
    }

    @PostMapping("/decisions/{decisionId}/document")
    @RequiresPermission(PeoplePermissions.DECISION_UPDATE)
    public Generated document(@PathVariable UUID decisionId) {
        return decisions.generate(decisionId);
    }

    @PostMapping(path = "/decisions/{decisionId}/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PeoplePermissions.DECISION_UPDATE)
    public Decision sign(@PathVariable UUID decisionId, @RequestPart("file") MultipartFile file,
            @RequestParam("signDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signDate,
            @RequestParam("version") int version) throws IOException {
        return decisions.sign(decisionId, signDate, file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                version);
    }

    @PostMapping("/decisions/{decisionId}/cancel")
    @RequiresPermission(PeoplePermissions.DECISION_UPDATE)
    public Decision cancel(@PathVariable UUID decisionId, @Valid @RequestBody VersionRequest req) {
        return decisions.cancel(decisionId, req.version());
    }

    @PostMapping("/decisions/{decisionId}/reissue")
    @RequiresPermission(PeoplePermissions.DECISION_CREATE)
    public ResponseEntity<Decision> reissue(@PathVariable UUID decisionId, @Valid @RequestBody ReissueRequest req) {
        Decision d = decisions.reissue(decisionId, new DecisionRegister.Reissue(req.effectiveDate(),
                req.signatoryName(), req.signatoryTitle(), req.version()));
        return ResponseEntity.created(URI.create("/api/v1/people/decisions/" + d.id())).body(d);
    }
}
