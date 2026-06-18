# HAPI FHIR: Particionamiento vs Multitenancy

> Basado en: https://hapifhir.io/hapi-fhir/docs/server_jpa_partitioning/partitioning.html  
> Versión HAPI FHIR del proyecto: 8.4.0

---

## 1. Concepto raíz: Particionamiento

El **particionamiento** (introducido en HAPI FHIR 5.0.0) es el mecanismo base que permite agrupar recursos en compartimentos lógicos. Cada recurso en el servidor puede pertenecer a una **partición**, que no es más que un identificador arbitrario — un número entero con un nombre asociado.

### Componentes de identidad de una partición

| Componente | Tipo | Descripción |
|---|---|---|
| **Partition ID** | `INTEGER` | Identificador numérico de la partición. `NULL` = partición por defecto |
| **Partition Name** | `VARCHAR(200)` | Nombre legible, ej. `org-acme`, `tenant-123` |
| **Partition Date** | `DATE` | Opcional. Fecha asociada al recurso para políticas de expiración |

### Columnas en base de datos

El particionamiento añade dos columnas a las tablas de recursos (`HFJ_RESOURCE`, `HFJ_RES_VER`, tablas de índice):

```sql
PARTITION_ID    INTEGER  -- NULL significa "partición por defecto"
PARTITION_DATE  DATE     -- para particionado temporal
```

### Recursos NO particionables

Ciertos recursos de conformance **siempre van a la partición por defecto**, independientemente de la configuración:
`CapabilityStatement`, `CodeSystem`, `StructureDefinition`, `ValueSet`, `SearchParameter`, `NamingSystem`, entre otros.

---

## 2. Particionamiento vs Multitenancy — La diferencia clave

Son **dos conceptos en capas distintas**. El particionamiento es la **infraestructura técnica**; el multitenancy es el **caso de uso** que se construye encima.

| Aspecto | Particionamiento | Multitenancy |
|---|---|---|
| **¿Qué es?** | Mecanismo técnico de agrupación de recursos | Caso de uso: múltiples organizaciones en un servidor |
| **¿Dónde vive?** | Schema de BD (columnas `PARTITION_ID`) | Capa de aplicación (interceptors, URL) |
| **¿Hay aislamiento?** | Configurable (puede haber referencias cruzadas) | Sí, por diseño — cada tenant ve solo sus datos |
| **¿Cómo se identifica?** | Interceptor customizado (cualquier lógica) | `RequestTenantPartitionInterceptor` vía URL |
| **URL de acceso** | `POST /fhir/Patient` | `POST /fhir/tenant-abc/Patient` |
| **Configuración requerida** | `PartitionSettings` + interceptor | Lo anterior + `partitioning.request_tenant_partitioning_mode=true` |

### Analogía

- **Partición** = cajón con etiqueta en un archivero compartido.
- **Multitenancy** = archivero con llave donde cada inquilino tiene su cajón y **no puede abrir los demás**.

El particionamiento permite también otros casos de uso que NO son multitenancy:
- **Segmentación lógica**: datos de laboratorio vs. datos de encuesta en el mismo tenant.
- **Sharding geográfico**: recursos de región US-East en `partition_id=1`, EU-West en `partition_id=2`.

---

## 3. Modos de particionamiento disponibles

### Modo A — Particionamiento básico (sin multitenancy)

El interceptor decide la partición con lógica propia (cabecera HTTP, claim JWT, etc.). No hay separación en la URL.

```
POST /fhir/Patient
X-Partition-Name: org-acme   ← el interceptor lee esta cabecera
```

### Modo B — Multitenancy por URL (`request_tenant_partitioning_mode`)

El tenant se embebe en la URL. HAPI extrae el primer segmento del path como nombre de partición y lo resuelve automáticamente.

```
POST /fhir/P1/Patient    → Partición "P1"
GET  /fhir/acme/Patient  → Partición "acme"
```

Requiere registrar `RequestTenantPartitionInterceptor`. El nombre de partición en la URL **debe coincidir** con una partición registrada en el servidor.

### Modo C — Database Partition Mode (experimental, HAPI 8.0.0+)

Rediseño profundo del schema:
- El `PARTITION_ID` se incorpora a las **claves primarias** y **foreign keys** de todas las tablas.
- Las queries SQL incluyen `PARTITION_ID` en `WHERE` y `JOIN ON`.
- La partición por defecto **no puede ser NULL** (debe ser un entero, ej. `0`).

Permite que el motor de base de datos use particionamiento nativo del RDBMS (ej. PostgreSQL Table Partitioning) sin modificaciones de schema adicionales.

**Limitaciones actuales:**
- No compatible con MDM (Master Data Management).
- No hay migración automática desde el modo clásico.
- Debe configurarse **antes del primer arranque** con el schema vacío.

---

## 4. Restricción crítica de IDs de recursos

> "Solo puede existir **un recurso con ID `Patient/1`** en todo el servidor, y debe estar en una sola partición."

Esto significa que los IDs de recursos son **globales entre particiones**. Si el `Patient/1` está en la partición `acme`, **ninguna otra partición puede tener un `Patient/1`**.

**Implicación de seguridad**: un usuario de un tenant podría inferir la existencia de recursos en otro tenant haciendo peticiones con IDs específicos. Si el aislamiento estricto es un requisito, deben usarse IDs asignados por el servidor (no por el cliente) y configurar permisos de lectura que devuelvan 404 en lugar de 403 para recursos de otras particiones.

---

## 5. Cómo funciona el interceptor de particionamiento

HAPI FHIR usa **pointcuts** (puntos de corte) para que el código de aplicación indique a qué partición pertenece cada operación:

| Pointcut | Cuándo se invoca |
|---|---|
| `STORAGE_PARTITION_IDENTIFY_CREATE` | Al crear un recurso (obligatorio) |
| `STORAGE_PARTITION_IDENTIFY_READ` | Al leer/buscar recursos (opcional, por defecto busca en todas) |

El interceptor recibe el `RequestDetails` y debe retornar un `RequestPartitionId`.

```java
// Ejemplo: leer partición desde cabecera HTTP
@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_CREATE)
public RequestPartitionId identifyPartition(RequestDetails requestDetails) {
    String tenantName = requestDetails.getHeader("X-Tenant-Id");
    return RequestPartitionId.fromPartitionName(tenantName);
}
```

En modo multitenancy por URL (`RequestTenantPartitionInterceptor`), este interceptor ya viene implementado por HAPI y no hay que escribirlo.

---

## 6. Referencias cruzadas entre particiones

Configurable mediante `allow_references_across_partitions`:

- **`true`** (por defecto): un recurso en partición `A` puede referenciar un recurso en partición `B`. Útil para segmentación lógica donde los datos comparten catálogos.
- **`false`**: aislamiento estricto. Ninguna referencia puede cruzar particiones. Recomendado para multitenancy real donde los tenants no deben ver datos ajenos.

---

## 7. Limitaciones conocidas

| Limitación | Detalle |
|---|---|
| `_history` cross-partition | No soportado |
| CapabilityStatement | No es partition-aware |
| Búsquedas encadenadas en modo patient compartment | Restringidas |
| Operaciones de paquetes (`$package`) | Solo afectan la partición por defecto |
| Elasticsearch | No optimizado para particiones |

---

## 8. Configuración actual en FarmSuite

El servidor HAPI FHIR del proyecto (`application.yaml`) tiene habilitado tanto multitenancy como Database Partition Mode:

```yaml
hapi:
  fhir:
    partitioning:
      allow_references_across_partitions: true         # referencias cruzadas permitidas
      partitioning_include_in_search_hashes: false     # no incluir partition_id en hashes de búsqueda
      request_tenant_partitioning_mode: true           # ← Multitenancy por URL activo
      conditional-create-duplicate-identifiers-enabled: false
      database-partition-mode-enabled: true            # ← DB Partition Mode (HAPI 8.0+)
```

Con esta configuración, las peticiones deben incluir el tenant en la URL:

```
GET  https://farmsuite.org/fhir/{tenant}/Patient
POST https://farmsuite.org/fhir/{tenant}/Observation
```

El `{tenant}` en la URL se mapea directamente al nombre de la partición registrada en HAPI. Como `allow_references_across_partitions: true`, los recursos de un tenant pueden referenciar recursos de otros tenants (útil para terminología compartida o recursos administrativos comunes).

---

## 9. Resumen visual

```
PARTICIONAMIENTO (infraestructura)
│
├── Caso de uso: Multitenancy
│   └── Tenant A ─── Partición "tenant-a" ─── BD: PARTITION_ID = 1
│   └── Tenant B ─── Partición "tenant-b" ─── BD: PARTITION_ID = 2
│
├── Caso de uso: Segmentación lógica
│   └── Lab data   ─── Partición "lab"    ─── BD: PARTITION_ID = 10
│   └── Survey data ── Partición "survey" ─── BD: PARTITION_ID = 11
│
└── Caso de uso: Sharding geográfico
    └── US-East ─── Partición "us-east" ─── BD: PARTITION_ID = 100
    └── EU-West ─── Partición "eu-west" ─── BD: PARTITION_ID = 200

DATABASE PARTITION MODE (HAPI 8.0+)
└── PARTITION_ID forma parte de PRIMARY KEY y FOREIGN KEY
    └── El RDBMS puede usar Table Partitioning nativo
    └── La partición por defecto = entero (0), nunca NULL
```
