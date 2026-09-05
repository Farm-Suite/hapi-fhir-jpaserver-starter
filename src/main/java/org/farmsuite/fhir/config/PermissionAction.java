package org.farmsuite.fhir.config;

import java.util.EnumSet;
import java.util.Set;

/**
 * Acciones que puede llevar el claim {@code permissions} del JWT, codificadas como un
 * carácter por acción (formato SMART-like: "Patient.rs" = read+search sobre Patient).
 */
enum PermissionAction {
	READ('r'),
	SEARCH('s'),
	CREATE('c'),
	UPDATE('u'),
	DELETE('d'),
	PATCH('p');

	private final char code;

	PermissionAction(char code) {
		this.code = code;
	}

	static Set<PermissionAction> parse(String actionCodes) {
		Set<PermissionAction> actions = EnumSet.noneOf(PermissionAction.class);
		for (char c : actionCodes.toCharArray()) {
			actions.add(fromCode(c));
		}
		return actions;
	}

	private static PermissionAction fromCode(char code) {
		for (PermissionAction action : values()) {
			if (action.code == code) {
				return action;
			}
		}
		throw new IllegalArgumentException("Código de acción de permiso desconocido: '" + code + "'");
	}
}
