package org.farmsuite.fhir.consent;

/** Lo que importa de un Patient para decidir un acceso: de qué tipo es y qué organización lo gestiona. */
public record PatientFacts(String patientId, SubjectKind kind, String managingOrganizationId) {
}
