# hapi-fhir-jpaserver-starter — FarmSuite

Servidor FHIR central de FarmSuite (R5, HAPI FHIR 8.4.0, Java 17). Es un **fork** de
`hapifhir/hapi-fhir-jpaserver-starter`: el `README.md` es el del proyecto
original y no describe lo propio de FarmSuite — usar este archivo.

Todo lo propio vive en el paquete **`org.farmsuite.fhir`** (`config/`, `interceptors/`, `usecase/`).
Evitar tocar el código del starter original (`ca.uhn.fhir.jpa.starter`) para no complicar merges
con upstream; si hace falta, extender desde `org.farmsuite.fhir`.

## Comandos

```bash
mvn -q compile
mvn test -Dtest=<Clase>       # la suite completa es lenta
```

Arrancar con la run configuration de IntelliJ `Application` (puerto **8081**, `/fhir`). Con
Keycloak local, activar el perfil `sso-local`.

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
- Por defecto el local corre contra un Postgres local propio (`Infra/local/docker-compose.yml`,
  perfil `local-db` activo en `application.yaml`), no contra la de pre.
