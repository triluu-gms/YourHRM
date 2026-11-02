package vn.yourhrm.people.internal.web.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.person.PersonMergeService;
import vn.yourhrm.people.internal.application.person.PersonMergeService.MergePreview;
import vn.yourhrm.people.internal.application.person.PersonService;
import vn.yourhrm.people.internal.application.person.PersonService.CreatePerson;
import vn.yourhrm.people.internal.application.person.PersonService.Field;
import vn.yourhrm.people.internal.application.person.PersonService.IdentifierChange;
import vn.yourhrm.people.internal.application.person.PersonService.Identifiers;
import vn.yourhrm.people.internal.application.person.PersonService.MatchQuery;
import vn.yourhrm.people.internal.application.person.PersonService.MatchResult;
import vn.yourhrm.people.internal.application.person.PersonService.PersonData;
import vn.yourhrm.people.internal.application.person.PersonService.PersonView;
import vn.yourhrm.people.internal.application.person.PersonService.Unmasked;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Persons (EP-PPL-01: US-PPL-002/003/004/008/009, US-PPL-085). Responses are masked; P3 values are written through
 * {@code /identifiers} and shown only through {@code /unmask} (purpose + audit). Scope: a person is in scope through any
 * of their employments; a person without employment needs a tenant-wide grant.
 */
@RestController
@RequestMapping("/api/v1/people/persons")
class PersonController {

    private final PersonService persons;
    private final PersonMergeService merges;

    PersonController(PersonService persons, PersonMergeService merges) {
        this.persons = persons;
        this.merges = merges;
    }

    record PersonDataRequest(
            @NotBlank @Size(max = 100) String familyName,
            @Size(max = 100) String middleName,
            @NotBlank @Size(max = 100) String givenName,
            @Size(max = 200) String latinName,
            @Size(max = 100) String preferredName,
            LocalDate birthDate,
            @Size(max = 20) String genderCode,
            @Size(min = 2, max = 2) String nationalityCode,
            @Size(max = 32) String hiFacilityCode,
            String siBookCustody) {

        PersonData toData() {
            return new PersonData(familyName, middleName, givenName, latinName, preferredName, birthDate, genderCode,
                    nationalityCode, hiFacilityCode, siBookCustody);
        }
    }

    record IdentifiersRequest(String nationalIdType, @Size(max = 32) String nationalId, @Size(max = 32) String taxCode,
            @Size(max = 32) String legacyTaxCode, @Size(max = 32) String siNumber, @Size(max = 32) String hiCardNo,
            @Size(max = 32) String ethnicityCode, @Size(max = 32) String religionCode) {

        Identifiers toIdentifiers() {
            return new Identifiers(nationalIdType, nationalId, taxCode, legacyTaxCode, siNumber, hiCardNo,
                    ethnicityCode, religionCode);
        }
    }

    record CreatePersonRequest(@NotNull @Valid PersonDataRequest person, @Valid IdentifiersRequest identifiers,
            boolean acknowledgeWarnings) {
    }

    record UpdatePersonRequest(@NotNull Integer version, @NotNull @Valid PersonDataRequest person) {
    }

    record ChangeIdentifiersRequest(@NotNull Integer version, @Valid IdentifiersRequest set, Set<Field> clear) {
    }

    record MatchRequest(String nationalIdType, String nationalId, String nationalityCode, String passportNo,
            String taxCode, String siNumber) {
    }

    record UnmaskRequest(@NotEmpty Set<Field> fields, @NotBlank @Size(max = 200) String purpose) {
    }

    record MergeRequest(@NotNull UUID duplicatePersonId, @NotBlank @Size(max = 500) String reason,
            @NotNull Integer version) {
    }

    @PostMapping
    @RequiresPermission(PeoplePermissions.PERSON_CREATE)
    public ResponseEntity<PersonView> create(@Valid @RequestBody CreatePersonRequest req) {
        PersonView created = persons.create(new CreatePerson(req.person().toData(),
                req.identifiers() == null ? null : req.identifiers().toIdentifiers(), "APP", req.acknowledgeWarnings()));
        return ResponseEntity.created(URI.create("/api/v1/people/persons/" + created.id())).body(created);
    }

    /** Duplicate check before creating (US-PPL-003): blocking and warning candidates, masked. */
    @PostMapping("/match")
    @RequiresPermission(PeoplePermissions.PERSON_CREATE)
    public MatchResult match(@RequestBody MatchRequest req) {
        return persons.match(new MatchQuery(req.nationalIdType(), req.nationalId(), req.nationalityCode(),
                req.passportNo(), req.taxCode(), req.siNumber()));
    }

    @GetMapping("/{personId}")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public PersonView get(@PathVariable UUID personId) {
        return persons.get(personId);
    }

    @PutMapping("/{personId}")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public PersonView update(@PathVariable UUID personId, @Valid @RequestBody UpdatePersonRequest req) {
        return persons.update(personId, req.version(), req.person().toData());
    }

    @PutMapping("/{personId}/identifiers")
    @RequiresPermission(PeoplePermissions.PERSON_UPDATE)
    public PersonView changeIdentifiers(@PathVariable UUID personId, @Valid @RequestBody ChangeIdentifiersRequest req) {
        return persons.changeIdentifiers(personId, req.version(), new IdentifierChange(
                req.set() == null ? null : req.set().toIdentifiers(), req.clear()));
    }

    /** Shows P3 values (US-PPL-085): permission + purpose, audited. */
    @PostMapping("/{personId}/unmask")
    @RequiresPermission(PeoplePermissions.PERSON_VIEW_SENSITIVE)
    public Unmasked unmask(@PathVariable UUID personId, @Valid @RequestBody UnmaskRequest req) {
        return persons.unmask(personId, req.fields(), req.purpose());
    }

    /** What merging {@code duplicatePersonId} into this person would move, and what blocks it (US-PPL-004). */
    @GetMapping("/{personId}/merge-preview")
    @RequiresPermission(PeoplePermissions.PERSON_MERGE)
    public MergePreview mergePreview(@PathVariable UUID personId, @RequestParam UUID duplicatePersonId) {
        return merges.preview(personId, duplicatePersonId);
    }

    @PostMapping("/{personId}/merge")
    @RequiresPermission(PeoplePermissions.PERSON_MERGE)
    public MergePreview merge(@PathVariable UUID personId, @Valid @RequestBody MergeRequest req) {
        return merges.merge(personId, req.duplicatePersonId(), req.version(), req.reason());
    }
}
