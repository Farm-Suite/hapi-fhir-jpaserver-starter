package org.farmsuite.fhir.consent;

import org.hl7.fhir.r5.model.Patient;

/**
 * Qué es un {@code Patient} en FarmSuite: una persona, un animal o una planta (un árbol). FHIR R5
 * cubre personas y animales en {@code Patient} y no dice nada de plantas, y no tiene un elemento
 * para distinguirlos, así que el tipo lo da el <b>perfil</b> ({@code meta.profile}), que es además
 * lo que ya decide el formulario de la app. Solo las personas están sujetas al RGPD (consentimiento
 * y fail-closed de auditoría); de un animal o un árbol decide quien lo posee.
 *
 * <p>Sin perfil, o con el perfil de persona, es una {@link #PERSON}: por defecto se protege.
 */
public enum SubjectKind {
	PERSON,
	ANIMAL,
	PLANT;

	public static final String ANIMAL_PROFILE = "https://farmsuite.org/fhir/DEFAULT/StructureDefinition/patient-animal";
	public static final String PLANT_PROFILE = "https://farmsuite.org/fhir/DEFAULT/StructureDefinition/patient-plant";

	public static SubjectKind of(Patient patient) {
		if (patient != null) {
			for (var profile : patient.getMeta().getProfile()) {
				String url = profile.getValue();
				if (ANIMAL_PROFILE.equals(url)) return ANIMAL;
				if (PLANT_PROFILE.equals(url)) return PLANT;
			}
		}
		return PERSON;
	}
}
