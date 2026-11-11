package vn.yourhrm.people.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * People's handlers for the SaaS data-subject-request orchestration (BR-PPL-153, US-PPL-088, OUT-33; Decree 13/2023,
 * PDPL 2025 {@code [verify-legal]}). SaaS (not built yet) opens the DSR, checks identity and deadlines, then calls these
 * handlers; until then HR / the DPO trigger them through People's REST endpoints. Both run in the tenant context of the
 * caller, need {@code hr.dsr.execute} and are audited step by step.
 */
public interface PrivacyRequests {

    /**
     * The access / portability package of a person: every People record of the person as JSON (P3 values decrypted
     * only inside the package) and, when the tenant has published the template {@code HR_DSR_EXPORT}, a human-readable
     * PDF in Core's document service.
     */
    ExportPackage exportPersonData(UUID personId, String requestRef);

    /**
     * Erasure: clears what no law requires to keep, keeps the labour / social-insurance / tax records for the legal
     * retention period, marks the person anonymised and crypto-shreds where allowed (family members without retained
     * claims; the person once the retention period is over). Refused while the person has a live employment.
     */
    AnonymisationConfirmation anonymise(UUID personId, String requestRef);

    /**
     * @param data       sections by table ({@code person}, {@code employment}, {@code salary_record} ...), each a list of
     *                   rows; encrypted columns are replaced by their decrypted value
     * @param pdfDocumentId null when no template is published ({@code warnings} says so)
     */
    record ExportPackage(UUID personId, String requestRef, Instant generatedAt, Map<String, List<Map<String, Object>>> data,
            UUID pdfDocumentId, List<String> warnings) {
    }

    /**
     * OUT-33 confirmation sent back to the DSR orchestration.
     *
     * @param mode          RESTRICTED (retention running: only optional data cleared) or FULL (identity anonymised,
     *                      subject key destroyed)
     * @param retainedUntil end of the legal retention of the retained records (null when FULL)
     */
    record AnonymisationConfirmation(UUID personId, String requestRef, String mode, LocalDate retainedUntil,
            List<String> cleared, List<String> retained, int shreddedSubjects, Instant completedAt) {
    }
}
