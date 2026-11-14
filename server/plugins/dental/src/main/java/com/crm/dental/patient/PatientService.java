package com.crm.dental.patient;

import com.crm.dental.DentalErrors;
import com.crm.dental.IfMatch;
import com.crm.dental.Names;
import com.crm.dental.Phones;
import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Patients: search by phone or name, create and update. The audit log gets field names only; the values (name,
 * phone, date of birth, identity number, address) stay in the patient row.
 */
@Service
@RequiredArgsConstructor
public class PatientService {

    static final String ENTITY_TYPE = "Patient";
    static final String AUDIT_CREATE = "PATIENT_CREATE";
    static final String AUDIT_UPDATE = "PATIENT_UPDATE";

    /** Upper bound of a search result. */
    static final int MAX_RESULTS = 50;

    private static final Set<String> ID_TYPES = Set.of("CCCD", "PASSPORT", "OTHER");
    private static final LocalDate EARLIEST_BIRTH = LocalDate.of(1900, 1, 1);

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock dentalClock;
    private final PatientRepository patients;
    private final ChangeRecorder changes;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    /**
     * Up to {@value #MAX_RESULTS} patients by name. {@code phone} is normalised and matched exactly; {@code q} is a
     * accent- and case-insensitive contains-match on the name; both together narrow each other.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_INVALID_QUERY} (400) when neither is given,
     *         {@code DENTAL_INVALID_PHONE} (400) for a phone that is not a Vietnamese mobile number
     */
    @Transactional(readOnly = true)
    public List<PatientView> search(String phone, String q) {
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasName = q != null && !q.isBlank();
        if (!hasPhone && !hasName) {
            throw DentalErrors.invalidQuery("Give a phone or a name (q) to search for");
        }
        PageRequest page = PageRequest.of(0, MAX_RESULTS);
        String normalized = hasPhone ? Phones.normalize(phone) : null;
        String pattern = hasName ? "%" + escapeLike(Names.fold(q)) + "%" : null;
        List<Patient> found;
        if (hasPhone && hasName) {
            found = patients.findByPhoneAndNameLike(normalized, pattern, page);
        } else if (hasPhone) {
            found = patients.findByPhone(normalized, page);
        } else {
            found = patients.findByNameLike(pattern, page);
        }
        return found.stream().map(PatientView::of).toList();
    }

    @Transactional
    public PatientView create(PatientRequest request) {
        return PatientView.of(createEntity(request));
    }

    /** Used by the booking's inline new patient, inside its transaction. */
    @Transactional
    public Patient createEntity(PatientRequest request) {
        Patient patient = new Patient();
        apply(patient, request);
        patient.setCreatedAt(Instant.now(dentalClock).truncatedTo(ChronoUnit.MICROS));
        Patient saved = patients.saveAndFlush(patient);
        changes.record(ENTITY_TYPE, saved.getId(), saved.getVersion(), ChangeOp.CREATE);
        audit.record(ENTITY_TYPE, saved.getId(), AUDIT_CREATE, null, json(Map.of("fields", filledFields(saved))));
        return saved;
    }

    /**
     * Replaces the patient's data.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND} (404),
     *         {@code DENTAL_VERSION_REQUIRED} (428); a stale {@code If-Match} is a 409
     */
    @Transactional
    public PatientView update(UUID id, PatientRequest request, String ifMatch) {
        Patient patient = patients.findById(id).orElseThrow(() -> DentalErrors.notFound("Patient not found"));
        IfMatch.requireVersion(ifMatch, patient.getVersion(), Patient.class, id);
        return change(patient, request);
    }

    /**
     * Replaces the data of a patient the caller has loaded (and checked the version of): the same rules, name
     * folding, audit (field names only) and change event as {@link #update}. Used when staff apply a pre-visit form.
     */
    @Transactional
    public PatientView applyTo(Patient patient, PatientRequest request) {
        return change(patient, request);
    }

    private PatientView change(Patient patient, PatientRequest request) {
        Map<String, Object> before = snapshot(patient);
        apply(patient, request);
        List<String> changed = new ArrayList<>();
        Map<String, Object> after = snapshot(patient);
        after.forEach((field, value) -> {
            if (!Objects.equals(value, before.get(field))) {
                changed.add(field);
            }
        });
        Patient saved = patients.saveAndFlush(patient);
        changes.record(ENTITY_TYPE, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);
        audit.record(ENTITY_TYPE, saved.getId(), AUDIT_UPDATE, null, json(Map.of("changedFields", changed)));
        return PatientView.of(saved);
    }

    private void apply(Patient patient, PatientRequest request) {
        LocalDate today = LocalDate.now(dentalClock);
        if (request.dateOfBirth().isAfter(today) || request.dateOfBirth().isBefore(EARLIEST_BIRTH)) {
            throw DentalErrors.invalidPatient("Date of birth is not a plausible date");
        }
        String idType = blankToNull(request.idType());
        if (idType != null && !ID_TYPES.contains(idType)) {
            throw DentalErrors.invalidPatient("idType must be CCCD, PASSPORT or OTHER");
        }
        String nationality = blankToNull(request.nationality());
        if (nationality != null && !nationality.matches("[A-Z]{2}")) {
            throw DentalErrors.invalidPatient("nationality must be an ISO 3166-1 alpha-2 code");
        }
        patient.setFullName(request.fullName().trim());
        patient.setNameSearch(Names.fold(patient.getFullName()));
        patient.setPhone(Phones.normalize(request.phone()));
        patient.setDateOfBirth(request.dateOfBirth());
        patient.setIdType(idType);
        patient.setIdNumber(blankToNull(request.idNumber()));
        patient.setNationality(nationality);
        patient.setAddress(blankToNull(request.address()));
    }

    /** Field name to value, for comparing before and after; the values themselves never leave this class. */
    private static Map<String, Object> snapshot(Patient patient) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("fullName", patient.getFullName());
        fields.put("phone", patient.getPhone());
        fields.put("dateOfBirth", patient.getDateOfBirth());
        fields.put("idType", patient.getIdType());
        fields.put("idNumber", patient.getIdNumber());
        fields.put("nationality", patient.getNationality());
        fields.put("address", patient.getAddress());
        return fields;
    }

    private static List<String> filledFields(Patient patient) {
        return snapshot(patient).entrySet().stream().filter(e -> e.getValue() != null).map(Map.Entry::getKey).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Makes {@code %}, {@code _} and the escape character {@code \} match literally in a LIKE pattern. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Patient audit entry could not be serialized", e);
        }
    }
}
