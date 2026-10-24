package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** El id de un recurso lo asigna el servidor: un usuario no puede crearlo con un PUT a un id que elige él. */
class ServerAssignedIdInterceptorTest {

	private UserSessionRepository sessions;
	private ServerAssignedIdInterceptor interceptor;

	@BeforeEach
	void setUp() {
		sessions = mock(UserSessionRepository.class);
		interceptor = new ServerAssignedIdInterceptor(sessions);
	}

	private static ServletRequestDetails request(RestOperationTypeEnum operation, String id) {
		ServletRequestDetails details = new ServletRequestDetails();
		details.setRestOperationType(operation);
		if (id != null) details.setId(new IdType("Task", id));
		return details;
	}

	@Test
	void creatingThroughAnUpdateWithAChosenIdIsRejected() {
		assertThatThrownBy(() -> interceptor.onCreated(new Task(), request(RestOperationTypeEnum.UPDATE, "mi-id")))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("Task")
			.hasMessageContaining("mi-id")
			.hasMessageContaining("asigna el servidor");
	}

	@Test
	void aPlainPostWithoutIdIsFine() {
		assertThatCode(() -> interceptor.onCreated(new Task(), request(RestOperationTypeEnum.CREATE, null))).doesNotThrowAnyException();
	}

	@Test
	void anUpdateWithoutAnIdPartIsNotRejected() {
		assertThatCode(() -> interceptor.onCreated(new Task(), request(RestOperationTypeEnum.UPDATE, null))).doesNotThrowAnyException();
	}

	@Test
	void theServiceAccountMayChooseIds() {
		when(sessions.isAdmin()).thenReturn(true);

		assertThatCode(() -> interceptor.onCreated(new Task(), request(RestOperationTypeEnum.UPDATE, "mi-id"))).doesNotThrowAnyException();
	}

	@Test
	void internalSystemCallsMayChooseIds() {
		SystemRequestDetails system = new SystemRequestDetails();
		system.setRestOperationType(RestOperationTypeEnum.UPDATE);
		system.setId(new IdType("Task", "mi-id"));

		assertThatCode(() -> interceptor.onCreated(new Task(), system)).doesNotThrowAnyException();
	}

	@Test
	void withoutRequestDetailsThereIsNothingToCheck() {
		assertThatCode(() -> interceptor.onCreated(new Task(), null)).doesNotThrowAnyException();
	}
}
