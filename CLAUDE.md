# hapi-fhir-jpaserver-starter — FarmSuite

Servidor FHIR central de FarmSuite (R5, HAPI FHIR 8.4.0, Java 17). Es un **fork** de
`hapifhir/hapi-fhir-jpaserver-starter`: el `AGENTS.md` y el `README.md` son los del proyecto
original y no describen lo propio de FarmSuite — usar este archivo.

Todo lo propio vive en el paquete **`org.farmsuite.fhir`** (`config/`, `interceptors/`, `usecase/`).
Evitar tocar el código del starter original (`ca.uhn.fhir.jpa.starter`) para no complicar merges
con upstream; si hace falta, extender desde `org.farmsuite.fhir`.

Referencia completa (autorización `FarmSuiteRules`, particiones, interceptores, SearchParameters,
configuración): skill **`farmsuite:repo-hapi-fhir`**. Mecanismos: `farmsuite:arquitectura-multitenancy`,
`farmsuite:arquitectura-permisos`, `farmsuite:arquitectura-espejos-fhir-bd`.

## Comandos

```bash
mvn -q compile
mvn test -Dtest=<Clase>       # la suite completa es lenta
```

Arrancar con la run configuration de IntelliJ `Application` (puerto **8081**, `/fhir`). Con
Keycloak local, activar el perfil `sso-local`. Entorno: `farmsuite:flujo-entorno-local`.

## Reglas

- Interceptores nuevos: registrarlos en `hapi.fhir.custom-interceptor-classes` de
  `application.yaml` (el orden importa; no se autodetectan).
- SearchParameters nuevos: JSON en `src/main/resources/custom-search-parameters/` (se instalan y
  reindexan al arrancar).
- Reglas de acceso: siempre acotadas al tenant de la sesión; las reglas estructurales (no
  dependientes de plantilla de rol) van antes del bucle de permisos en `FarmSuiteRules`.
- Los hooks `STORAGE_PRECOMMIT_*` pueden recibir `RequestPartitionId` nulo: usar
  `RequestDetails.getTenantId()` como respaldo.
- Espejos hacia microservicios: best-effort, nunca bloquear la escritura FHIR.
- La base de datos local es la de pre (compartida): cuidado con datos de prueba.
