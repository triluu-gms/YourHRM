package vn.yourhrm.people.internal.web.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.person.IdentityDocumentService;
import vn.yourhrm.people.internal.application.person.IdentityDocumentService.DocumentData;
import vn.yourhrm.people.internal.application.person.IdentityDocumentService.DocumentView;
import vn.yourhrm.people.internal.application.person.IdentityDocumentService.Expiring;
import vn.yourhrm.people.internal.application.person.PersonAddressService;
import vn.yourhrm.people.internal.application.person.PersonAddressService.AddressData;
import vn.yourhrm.people.internal.application.person.PersonAddressService.AddressView;
import vn.yourhrm.people.internal.application.person.PersonContactService;
import vn.yourhrm.people.internal.application.person.PersonContactService.ContactData;
import vn.yourhrm.people.internal.application.person.PersonContactService.ContactView;
import vn.yourhrm.people.internal.application.person.QualificationService;
import vn.yourhrm.people.internal.application.person.QualificationService.QualificationData;
import vn.yourhrm.people.internal.application.person.QualificationService.QualificationView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Addresses, contacts, identity documents and qualification of a person (US-PPL-005/006/007/010). Reads need
 * {@code hr.employee.view}, writes {@code hr.person.update}, both in scope of one of the person's employments.
 */
@RestController
@RequestMapping("/api/v1/people/persons")
class PersonDetailsController {

    private final PersonAddressService addresses;
    private final PersonContactService contacts;
    private final IdentityDocumentService documents;
    private final QualificationService qualifications;

    PersonDetailsController(PersonAddressService addresses, PersonContactService contacts,
            IdentityDocumentService documents, QualificationService qualifications) {
        this.addresses = addresses;
        this.contacts = contacts;
        this.documents = documents;
        this.qualifications = qualifications;
    }

    record AddressRequest(@Size(min = 2, max = 2) String countryCode, @Size(max = 16) String provinceCode,
            @Size(max = 16) String communeCode, @Size(max = 500) String addressLine) {

        AddressData toData() {
            return new AddressData(countryCode, provinceCode, communeCode, addressLine);
        }
    }

    record AddAddressRequest(@NotBlank String addressType, @NotNull LocalDate validFrom,
            @NotNull @Valid AddressRequest address) {
    }

    record CorrectAddressRequest(@NotNull Integer version, @NotNull @Valid AddressRequest address) {
    }

    record EndAddressRequest(@NotNull Integer version, @NotNull LocalDate validTo) {
    }

    record ContactRequest(@NotBlank String contactType, @NotBlank @Size(max = 254) String value, Boolean primary) {
    }

    record ChangeContactRequest(@NotNull Integer version, @NotBlank @Size(max = 254) String value, Boolean primary) {
    }

    record DocumentRequest(String docType, @Size(max = 32) String docNo, @Size(min = 2, max = 2) String issuingCountryCode,
            LocalDate issueDate, @Size(max = 200) String issuePlace, LocalDate expiryDate,
            @Size(max = 32) String visaTypeCode, String status) {

        DocumentData toData() {
            return new DocumentData(docType, docNo, issuingCountryCode, issueDate, issuePlace, expiryDate, visaTypeCode,
                    status);
        }
    }

    record ChangeDocumentRequest(@NotNull Integer version, @NotNull @Valid DocumentRequest document) {
    }

    record AttachRequest(@NotNull UUID documentId) {
    }

    record PurposeRequest(@NotBlank @Size(max = 200) String purpose) {
    }

    record QualificationRequest(@NotBlank String qualificationLevelCode, String vocationalSkillLevelCode,
            String generalEducationCode, @Size(max = 200) String fieldOfStudy, @Size(max = 200) String institutionName,
            Integer graduationYear, Boolean highest) {

        QualificationData toData() {
            return new QualificationData(qualificationLevelCode, vocationalSkillLevelCode, generalEducationCode,
                    fieldOfStudy, institutionName, graduationYear, Boolean.TRUE.equals(highest));
        }
    }

    record ChangeQualificationRequest(@NotNull Integer version, @NotNull @Valid QualificationRequest qualification) {
    }

    // ---- addresses (US-PPL-005) ------------------------------------------------------------------------------------

    @GetMapping("/{personId}/addresses")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<AddressView> addresses(@PathVariable UUID personId,
            @RequestParam(name = "asOf", required = false) LocalDate asOf) {
        return addresses.list(personId, asOf);
    }

    @PostMapping("/{personId}/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public AddressView addAddress(@PathVariable UUID personId, @Valid @RequestBody AddAddressRequest req) {
        return addresses.add(personId, req.addressType(), req.validFrom(), req.address().toData());
    }

    @PutMapping("/{personId}/addresses/{addressId}")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public AddressView correctAddress(@PathVariable UUID personId, @PathVariable UUID addressId,
            @Valid @RequestBody CorrectAddressRequest req) {
        return addresses.correct(personId, addressId, req.version(), req.address().toData());
    }

    @PostMapping("/{personId}/addresses/{addressId}/end")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public AddressView endAddress(@PathVariable UUID personId, @PathVariable UUID addressId,
            @Valid @RequestBody EndAddressRequest req) {
        return addresses.end(personId, addressId, req.version(), req.validTo());
    }

    // ---- contacts (US-PPL-006) -------------------------------------------------------------------------------------

    @GetMapping("/{personId}/contacts")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<ContactView> contacts(@PathVariable UUID personId) {
        return contacts.list(personId);
    }

    @PostMapping("/{personId}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public ContactView addContact(@PathVariable UUID personId, @Valid @RequestBody ContactRequest req) {
        return contacts.add(personId, new ContactData(req.contactType(), req.value(), Boolean.TRUE.equals(req.primary())));
    }

    @PutMapping("/{personId}/contacts/{contactId}")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public ContactView changeContact(@PathVariable UUID personId, @PathVariable UUID contactId,
            @Valid @RequestBody ChangeContactRequest req) {
        return contacts.change(personId, contactId, req.version(), req.value(), Boolean.TRUE.equals(req.primary()));
    }

    @DeleteMapping("/{personId}/contacts/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public void removeContact(@PathVariable UUID personId, @PathVariable UUID contactId) {
        contacts.remove(personId, contactId);
    }

    // ---- identity documents (US-PPL-007) ---------------------------------------------------------------------------

    @GetMapping("/{personId}/identity-documents")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<DocumentView> identityDocuments(@PathVariable UUID personId) {
        return documents.list(personId);
    }

    @PostMapping("/{personId}/identity-documents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public DocumentView addIdentityDocument(@PathVariable UUID personId, @Valid @RequestBody DocumentRequest req) {
        return documents.add(personId, req.toData());
    }

    @PutMapping("/{personId}/identity-documents/{documentId}")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public DocumentView changeIdentityDocument(@PathVariable UUID personId, @PathVariable UUID documentId,
            @Valid @RequestBody ChangeDocumentRequest req) {
        return documents.change(personId, documentId, req.version(), req.document().toData());
    }

    /** Links the scan uploaded to Core's document service (type HR_IDENTITY_DOC_SCAN, owner this document). */
    @PutMapping("/{personId}/identity-documents/{documentId}/scan")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public DocumentView attachIdentityScan(@PathVariable UUID personId, @PathVariable UUID documentId,
            @Valid @RequestBody AttachRequest req) {
        return documents.attachScan(personId, documentId, req.documentId());
    }

    @PostMapping("/{personId}/identity-documents/{documentId}/unmask")
    @RequiresPermission(PeoplePermissions.PERSON_VIEW_SENSITIVE)
    public Map<String, String> unmaskIdentityDocument(@PathVariable UUID personId, @PathVariable UUID documentId,
            @Valid @RequestBody PurposeRequest req) {
        return documents.unmask(personId, documentId, req.purpose());
    }

    /** Report: identity documents of employees in scope expiring within {@code withinDays} (US-PPL-007). */
    @GetMapping("/expiring-identity-documents")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<Expiring> expiringIdentityDocuments(
            @RequestParam(name = "withinDays", defaultValue = "60") @Min(0) @Max(3660) int withinDays) {
        return documents.expiring(withinDays);
    }

    // ---- qualification (US-PPL-010) --------------------------------------------------------------------------------

    @GetMapping("/{personId}/qualifications")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public List<QualificationView> qualifications(@PathVariable UUID personId) {
        return qualifications.list(personId);
    }

    @PostMapping("/{personId}/qualifications")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public QualificationView addQualification(@PathVariable UUID personId, @Valid @RequestBody QualificationRequest req) {
        return qualifications.add(personId, req.toData());
    }

    @PutMapping("/{personId}/qualifications/{qualificationId}")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public QualificationView changeQualification(@PathVariable UUID personId, @PathVariable UUID qualificationId,
            @Valid @RequestBody ChangeQualificationRequest req) {
        return qualifications.change(personId, qualificationId, req.version(), req.qualification().toData());
    }

    @PutMapping("/{personId}/qualifications/{qualificationId}/scan")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public QualificationView attachQualificationScan(@PathVariable UUID personId, @PathVariable UUID qualificationId,
            @Valid @RequestBody AttachRequest req) {
        return qualifications.attachScan(personId, qualificationId, req.documentId());
    }
}
