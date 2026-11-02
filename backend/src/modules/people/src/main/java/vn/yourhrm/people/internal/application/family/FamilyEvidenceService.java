package vn.yourhrm.people.internal.application.family;

import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.core.api.Documents.DocumentView;
import vn.yourhrm.core.api.Documents.Upload;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Evidence of dependant claims (BR-PPL-016: "hồ sơ chứng minh"): files are stored by Core's document service as
 * documents of the family member ({@code hr.family_member}); a claim links the documents it relies on through
 * {@code hr.hr_document} rows. HR uploads with {@link FamilyCatalogues#DOC_EVIDENCE}, the employee in ESS with
 * {@link FamilyCatalogues#DOC_EVIDENCE_ESS}; Core checks the type's permission against the employee's employment.
 */
@Service
public class FamilyEvidenceService {

    private final FamilyMemberService members;
    private final EmployeeAccess access;
    private final Documents documents;
    private final BusinessClock clock;

    public FamilyEvidenceService(FamilyMemberService members, EmployeeAccess access, Documents documents,
            BusinessClock clock) {
        this.members = members;
        this.access = access;
        this.documents = documents;
        this.clock = clock;
    }

    /**
     * Stores an evidence file of a family member of {@code personId}. For {@code ess} the caller has already resolved
     * {@code personId} from their SELF scope.
     */
    @HrTransactional(readOnly = true)
    public DocumentView upload(UUID personId, UUID familyMemberId, boolean ess, String title, String fileName,
            String mediaType, byte[] content) {
        if (!ess) {
            access.requirePerson(PeoplePermissions.DEPENDANT_UPDATE, personId, clock.today());
        }
        members.load(personId, familyMemberId);
        return documents.upload(new Upload(ess ? FamilyCatalogues.DOC_EVIDENCE_ESS : FamilyCatalogues.DOC_EVIDENCE,
                familyMemberId, null, personId, title, null, fileName, mediaType, content));
    }
}
