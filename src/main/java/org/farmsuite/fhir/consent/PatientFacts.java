package org.farmsuite.fhir.consent;

/**
 * Lo que importa de un Patient para decidir un acceso: de qué tipo es y qué organización lo gestiona.
 * {@code unresolved}: el paciente existe pero no se pudo leer, así que no se sabe qué es; se trata
 * como persona y se deniega (fail-closed).
 */
public record PatientFacts(String patientId, SubjectKind kind, String managingOrganizationId, boolean unresolved) {

	public PatientFacts(String patientId, SubjectKind kind, String managingOrganizationId) {
		this(patientId, kind, managingOrganizationId, false);
	}

	public static PatientFacts unresolved(String patientId) {
		return new PatientFacts(patientId, SubjectKind.PERSON, null, true);
	}
}
