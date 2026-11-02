package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.SequenceService;
import vn.yourhrm.core.internal.application.SequenceService.SequenceCommand;
import vn.yourhrm.core.internal.application.SequenceService.SequenceView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Number series administration with preview (BR-CORE-085, US-CORE-086). Numbers themselves are taken by modules
 * through {@code core.api.Numbering}, inside their business transaction.
 */
@RestController
@RequestMapping("/api/v1/core/sequences")
class SequenceController {

    private final SequenceService sequences;

    SequenceController(SequenceService sequences) {
        this.sequences = sequences;
    }

    record CreateSequenceRequest(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,40}$") String code,
            @NotBlank @Size(max = 200) String name, String scopeLevel, @NotBlank @Size(max = 100) String pattern,
            String resetRule, Boolean gapFree, Long startValue, Integer incrementBy) {
    }

    record UpdateSequenceRequest(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 100) String pattern,
            @NotBlank String resetRule, Boolean gapFree, Integer incrementBy, String status, @NotNull Integer version) {
    }

    @GetMapping
    @RequiresPermission(value = CorePermissions.SEQUENCE_VIEW, tenantWide = true)
    public List<SequenceView> list() {
        return sequences.list();
    }

    @PostMapping
    @RequiresPermission(value = CorePermissions.SEQUENCE_UPDATE, tenantWide = true)
    public ResponseEntity<SequenceView> create(@Valid @RequestBody CreateSequenceRequest req) {
        SequenceView created = sequences.create(req.code(), new SequenceCommand(req.name(), req.scopeLevel(),
                req.pattern(), req.resetRule(), req.gapFree(), req.startValue(), req.incrementBy(), null));
        return ResponseEntity.created(URI.create("/api/v1/core/sequences/" + created.code())).body(created);
    }

    @PutMapping("/{code}")
    @RequiresPermission(value = CorePermissions.SEQUENCE_UPDATE, tenantWide = true)
    public SequenceView update(@PathVariable String code, @Valid @RequestBody UpdateSequenceRequest req) {
        return sequences.update(code, new SequenceCommand(req.name(), null, req.pattern(), req.resetRule(),
                req.gapFree(), null, req.incrementBy(), req.status()), req.version());
    }

    /** The number the next call would produce, without consuming it. */
    @GetMapping("/{code}/preview")
    @RequiresPermission(value = CorePermissions.SEQUENCE_VIEW, tenantWide = true)
    public Map<String, String> preview(@PathVariable String code,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "onDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate onDate) {
        return Map.of("next", sequences.preview(code, legalEntityId, onDate));
    }
}
