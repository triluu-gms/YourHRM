package vn.yourhrm.people.internal.application.person;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Minimal qualification (BR-PPL-019 restored by R3-07, US-PPL-010; {@code hr.education_qualification}): the
 * professional / technical qualification level and vocational skill level the labour book prints
 * ({@code hr.v_labour_book} reads the {@code is_highest} row). Levels come from Core code lists. Exactly one row may be
 * the highest: flagging another one moves the flag. Full education history and certificates are Phase 2.
 */
@Service
public class QualificationService {

    public record QualificationData(String qualificationLevelCode, String vocationalSkillLevelCode,
            String generalEducationCode, String fieldOfStudy, String institutionName, Integer graduationYear,
            boolean highest) {
    }

    public record QualificationView(UUID id, String qualificationLevelCode, String vocationalSkillLevelCode,
            String generalEducationCode, String fieldOfStudy, String institutionName, Integer graduationYear,
            boolean highest, UUID scanDocumentId, int version) {
    }

    private final HrDb db;
    private final PersonSupport support;

    public QualificationService(HrDb db, PersonSupport support) {
        this.db = db;
        this.support = support;
    }

    @HrTransactional(readOnly = true)
    public List<QualificationView> list(UUID personId) {
        support.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId);
        return db.jdbc().sql("""
                        select * from hr.education_qualification where tenant_id = :t and person_id = :p
                         order by is_highest desc, graduation_year desc nulls last""")
                .param("t", support.tenant()).param("p", personId).query((rs, n) -> view(rs)).list();
    }

    @HrTransactional
    public QualificationView add(UUID personId, QualificationData q) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        support.lockPerson(personId);
        QualificationData v = validate(q);
        UUID t = support.tenant();
        boolean first = !db.jdbc().sql("select exists (select 1 from hr.education_qualification where tenant_id = :t and person_id = :p)")
                .param("t", t).param("p", personId).query(Boolean.class).single();
        boolean highest = v.highest() || first;
        if (highest) {
            clearHighest(personId, null);
        }
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into hr.education_qualification (tenant_id, id, person_id, qualification_level_code,
                               vocational_skill_level_code, general_education_code, field_of_study, institution_name,
                               graduation_year, is_highest, created_by, updated_by)
                        values (:t, :id, :p, :q, :voc, :gen, :field, :inst, :year, :highest, :a, :a)""")
                .param("t", t).param("id", id).param("p", personId).param("q", v.qualificationLevelCode())
                .param("voc", v.vocationalSkillLevelCode()).param("gen", v.generalEducationCode())
                .param("field", v.fieldOfStudy()).param("inst", v.institutionName())
                .param("year", v.graduationYear() == null ? null : v.graduationYear().shortValue())
                .param("highest", highest).param("a", support.actor()).update();
        return find(id);
    }

    @HrTransactional
    public QualificationView change(UUID personId, UUID id, int version, QualificationData q) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        QualificationView current = owned(personId, id);
        QualificationData v = validate(q);
        if (current.highest() && !v.highest()) {
            throw ApiException.unprocessable("highest-qualification-required", "Flag another qualification as the "
                    + "highest instead of removing the flag");
        }
        if (v.highest() && !current.highest()) {
            clearHighest(personId, id);
        }
        int updated = db.jdbc().sql("""
                        update hr.education_qualification set qualification_level_code = :q,
                               vocational_skill_level_code = :voc, general_education_code = :gen, field_of_study = :field,
                               institution_name = :inst, graduation_year = :year, is_highest = :highest,
                               version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id and version = :v""")
                .param("q", v.qualificationLevelCode()).param("voc", v.vocationalSkillLevelCode())
                .param("gen", v.generalEducationCode()).param("field", v.fieldOfStudy())
                .param("inst", v.institutionName())
                .param("year", v.graduationYear() == null ? null : v.graduationYear().shortValue())
                .param("highest", v.highest()).param("a", support.actor()).param("t", support.tenant())
                .param("id", id).param("v", version).update();
        PersonSupport.requireUpdated(updated, "Qualification", id);
        return find(id);
    }

    @HrTransactional
    public QualificationView attachScan(UUID personId, UUID id, UUID documentId) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        owned(personId, id);
        support.requireDocument(documentId, PersonKeys.DOC_QUALIFICATION_SCAN, PersonKeys.OWNER_QUALIFICATION, id);
        db.jdbc().sql("""
                        update hr.education_qualification set document_id = :d, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("d", documentId).param("a", support.actor()).param("t", support.tenant()).param("id", id).update();
        return find(id);
    }

    private void clearHighest(UUID personId, UUID except) {
        db.jdbc().sql("""
                        update hr.education_qualification set is_highest = false, version = version + 1, updated_by = :a
                         where tenant_id = :t and person_id = :p and is_highest and (cast(:x as uuid) is null or id <> :x)""")
                .param("a", support.actor()).param("t", support.tenant()).param("p", personId).param("x", except).update();
    }

    private QualificationData validate(QualificationData q) {
        LocalDate today = support.today();
        if (q.qualificationLevelCode() == null || q.qualificationLevelCode().isBlank()) {
            throw ApiException.unprocessable("invalid-qualification", "The qualification level is required");
        }
        if (q.graduationYear() != null && (q.graduationYear() < 1900 || q.graduationYear() > today.getYear() + 1)) {
            throw ApiException.unprocessable("invalid-graduation-year", "The graduation year is out of range");
        }
        return new QualificationData(support.code(PersonKeys.CL_QUALIFICATION_LEVEL, q.qualificationLevelCode(), today),
                support.code(PersonKeys.CL_VOCATIONAL_SKILL_LEVEL, q.vocationalSkillLevelCode(), today),
                support.code(PersonKeys.CL_GENERAL_EDUCATION, q.generalEducationCode(), today),
                trim(q.fieldOfStudy()), trim(q.institutionName()), q.graduationYear(), q.highest());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private QualificationView owned(UUID personId, UUID id) {
        return db.jdbc().sql("select * from hr.education_qualification where tenant_id = :t and id = :id and person_id = :p")
                .param("t", support.tenant()).param("id", id).param("p", personId)
                .query((rs, n) -> view(rs)).optional().orElseThrow(() -> ApiException.notFound("Qualification", id));
    }

    private QualificationView find(UUID id) {
        return db.jdbc().sql("select * from hr.education_qualification where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> view(rs)).single();
    }

    private static QualificationView view(ResultSet rs) throws SQLException {
        Object year = rs.getObject("graduation_year");
        return new QualificationView(rs.getObject("id", UUID.class), rs.getString("qualification_level_code"),
                rs.getString("vocational_skill_level_code"), rs.getString("general_education_code"),
                rs.getString("field_of_study"), rs.getString("institution_name"),
                year == null ? null : ((Number) year).intValue(), rs.getBoolean("is_highest"),
                rs.getObject("document_id", UUID.class), rs.getInt("version"));
    }
}
