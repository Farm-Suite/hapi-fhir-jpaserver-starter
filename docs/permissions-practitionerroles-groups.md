# Permisos: PractitionerRole, Group y Permission

> Modelo de "plantillas de rol" para resolver permisos de usuario sin crear un recurso
> `Permission` por cada usuario individual. Involucra: `hapi-fhir-jpaserver-starter` (recursos FHIR),
> `SSO/sso-keycloak` (resolución en login) y `SSO/sso-interceptor` (consumo en microservicios).

---

## 1. Idea general

En vez de asignar permisos usuario por usuario, se definen **plantillas de rol** como recursos
`Group` de FHIR (`membership: definitional`). Un `PractitionerRole` es miembro de una o más
plantillas, y cada plantilla es el `actor` de uno o más recursos `Permission` que listan qué
acciones habilita.

```
PractitionerRole ──(member de)──> Group (membership: definitional)
                                       │
                                       └──(actor de)──> Permission (rule.activity.action = ["CONF_READ", ...])
```

En Keycloak:
- Cada usuario tiene un atributo `fhir_role_id` con el id de su `PractitionerRole`.
- Los grupos de Keycloak se nombran igual que los `id` de los recursos `Group` en FHIR
  (ej. grupo Keycloak `plantilla-medicos` ↔ `Group/plantilla-medicos`), aunque hoy la resolución
  real de permisos **no depende de los grupos de Keycloak**: se resuelve consultando el propio
  servidor FHIR (ver sección 3).

---

## 2. Modelo de datos FHIR

### `Group` — la plantilla

| Campo | Valor / uso |
|---|---|
| `type` | `practitioner` (no existe un tipo específico para `PractitionerRole`) |
| `membership` | `definitional` — obligatorio, filtra plantillas de grupos "enumerados" normales |
| `member[].entity` | `Reference` al `PractitionerRole` miembro |
| `member[].inactive` | si es `true`, la membresía se ignora al resolver |
| `member[].period` | vigencia de la membresía; fuera de rango se ignora |

`inactive` y `period` **no son parámetros de búsqueda indexables** de FHIR — se filtran en memoria
sobre el `Bundle` ya traído, no en la query (ver `PermissionService.hasActiveMembership`).

### `Permission` — la regla

| Campo | Valor / uso |
|---|---|
| `status` | `active` (obligatorio) |
| `rule[].type` | `permit` \| `deny` (`Enumerations.ConsentProvisionType`, obligatorio) |
| `rule[].activity[].actor[]` | `Reference` al/los `Group` que tienen esta regla |
| `rule[].activity[].action[]` | `CodeableConcept` — el código de cada `coding` es el permiso en sí (ej. `CONF_READ`, `CONF_WRITE`) |

La convención de códigos de `action` **es la misma** que ya usa `Configuration-MIC` en
`@PreAuthorize("@securityService.hasPermissionOrAdmin('CONF_READ')")` — son strings opacos, sin
namespacing especial, comparados por `.contains(...)`.

### Ejemplo de bodies para pruebas

Crear la plantilla con id fijo (más cómodo para pruebas, vía `PUT`):

```http
PUT /fhir/{tenant}/Group/plantilla-medicos
Content-Type: application/fhir+json
```
```json
{
  "resourceType": "Group",
  "id": "plantilla-medicos",
  "type": "practitioner",
  "membership": "definitional",
  "name": "plantilla-medicos",
  "member": [
    { "entity": { "reference": "PractitionerRole/{ID_PRACTITIONER_ROLE}" } }
  ]
}
```

Crear el `Permission` que referencia esa plantilla como actor:

```http
POST /fhir/{tenant}/Permission
Content-Type: application/fhir+json
```
```json
{
  "resourceType": "Permission",
  "status": "active",
  "rule": [
    {
      "type": "permit",
      "activity": [
        {
          "actor": [{ "reference": "Group/plantilla-medicos" }],
          "action": [
            { "coding": [{ "code": "CONF_READ" }] },
            { "coding": [{ "code": "CONF_WRITE" }] }
          ]
        }
      ]
    }
  ]
}
```

> **Tenant en la URL**: con `request_tenant_partitioning_mode: true` (ver
> [`partitioning-vs-multitenancy.md`](./partitioning-vs-multitenancy.md)), toda petición va bajo
> `/fhir/{tenant}/...`. Postear a `/fhir/Permission` sin tenant produce un enrutado incorrecto del
> lado del servidor (error confuso tipo `expected "Bundle" but found "Permission"`).

---

## 3. Flujo de resolución (`PermissionService`)

Clase: `SSO/sso-keycloak/.../services/PermissionService.java`, método
`resolve(practitionerId, practitionerRoleId, tenantId)` → `List<String>`.

Se invoca desde `RoleSelectorForm.saveToSession(...)` (autenticador de Keycloak), una vez que el
usuario ya eligió organización/tenant y rol activo en el login.

```
1. GET Group?membership=definitional&member=PractitionerRole/{roleId}
   → candidatos a plantilla.

2. Filtro en memoria sobre el Bundle:
   descarta member.inactive=true o fuera de member.period vigente.
   → lista de ids de Group válidos.

3. GET Permission?actor=Group/{id1},Group/{id2},...
   (un solo request con OR vía ReferenceClientParam.hasAnyOfIds)
   → Bundle de Permission.

4. Aplana rule[].activity[].action[].coding[].code de todos los Permission
   → List<String> distinta (ej. ["CONF_READ", "CONF_WRITE"]).
```

Si no hay plantillas válidas, se devuelve `List.of()` sin llamar al segundo endpoint.

Pendiente (marcado con `TODO` en el código): un override de permisos a nivel de
`practitionerId` individual, por encima de lo que otorga la plantilla.

---

## 4. Dónde termina el resultado

```
PermissionService.resolve(...)
        │
        ▼
SessionNotes.setPermissionsInSessionNote(authSession, permissions)   [sso-keycloak]
        │   (session note "permissions", serializado como JSON string)
        ▼
   ⚠ FALTA: protocol mapper de Keycloak que copie el user session note
     "permissions" al claim "permissions" del token
        │
        ▼
JwtConverterUserSessionModel.getPermissionApplication(jwt)            [sso-interceptor]
   lee jwt.getClaim("permissions")
        │
        ▼
UserSessionModel.getPermission() → List<String>
        │
        ▼
ConfigurationSecurityService.hasPermission("CONF_READ")                [Configuration-MIC]
   sessionRepository.getUserSession().getPermission().contains(permission)
```

**Gap conocido:** hoy no existe ningún protocol mapper en `sso-keycloak` que copie el user
session note `permissions` al claim `permissions` del access token — el nombre del claim ya
coincide en ambos lados (`SessionNotes.PERMISSIONS` = `JwtConverterUserSessionModel.PERMISSION`
= `"permissions"`), pero falta la pieza que los conecta en tiempo de emisión del token. Sin esto,
`UserSessionModel.getPermission()` siempre verá una lista vacía en producción.

---

## 5. SearchParameter custom: `Permission?actor`

`Permission.rule.activity.actor` **no es un search parameter estándar** de la especificación FHIR
base — hay que registrarlo explícitamente en el servidor o la query `Permission?actor=...` no
indexa nada.

- Definición: `hapi-fhir-jpaserver-starter/src/main/resources/custom-search-parameters/permission-actor.json`
  (JSON versionado, no hardcodeado en Java).
- Carga: `org.farmsuite.fhir.config.CustomSearchParameterInitializer` — `ApplicationRunner` que:
  1. Lee todos los `classpath*:custom-search-parameters/*.json`.
  2. Hace upsert idempotente por `url` (vía `IFhirResourceDao.update(resource, "url=...", ...)`).
  3. Dispara manualmente un job de reindex (`JOB_REINDEX`) para los tipos en `base` (aquí
     `Permission`), con `RequestPartitionId.allPartitions()` explícito — necesario porque
     `Permission` **sí es particionable** (no está en la lista hardcodeada de recursos no
     particionables de HAPI: `SearchParameter`, `StructureDefinition`, `Questionnaire`,
     `CapabilityStatement`, `CompartmentDefinition`, `OperationDefinition`, `Library`,
     `ConceptMap`, `CodeSystem`, `ValueSet`, `NamingSystem`, `StructureMap`), y no hay un tenant
     conocido en el contexto de arranque para que el interceptor de partición lo resuelva solo.

Ver también la sección **"Search Parameters custom"** en `CLAUDE.md` (raíz del repo) para el
procedimiento general: cualquier filtro no estándar necesita su propio `SearchParameter` antes de
poder usarse.

---

## 6. Prueba end-to-end

1. Crear el `PractitionerRole` y anotar su `id`.
2. `PUT /fhir/{tenant}/Group/plantilla-medicos` con ese `PractitionerRole` como `member`.
3. `POST /fhir/{tenant}/Permission` con `actor: Group/plantilla-medicos` y las acciones deseadas.
4. `GET /fhir/{tenant}/Group?membership=definitional&member=PractitionerRole/{id}` → debe devolver
   el `Group` creado.
5. `GET /fhir/{tenant}/Permission?actor=Group/plantilla-medicos` → debe devolver el `Permission`.
6. Invocar `PermissionService.resolve(practitionerId, roleId, tenant)` → debe devolver
   `["CONF_READ", "CONF_WRITE"]`.
