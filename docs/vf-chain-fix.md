# VF-CHAIN-FIX — Cadena hash SIF rota por timezone (resolución)

> Bug detectado 2026-06-08. Tras reiniciar el backend, el job
> `AnomalyDetectionScheduler` reportaba **cadena de eventos SIF rota
> en TODAS las empresas** con el mensaje `"Hash recalculado no
> coincide"`, aunque el usuario NO había tocado la BD.

## Diagnóstico

Aplicado el pattern de **agentes paralelos** documentado en
`docs/agents-debug-pattern.md`. Dos agentes Explore lanzados:

1. Agente 1: rastreo del flujo INSERT del timestamp.
2. Agente 2: rastreo del flujo SELECT / verificación.

**Ambos convergieron en el mismo punto**:

- `SifEventRepository.java:228` (lado SELECT) usaba
  `OffsetDateTime.ofInstant(gen.toInstant(), ZoneOffset.UTC)` sobre
  un `java.sql.Timestamp` cuya interpretación depende de la zona
  horaria del driver / JVM.
- `SifEventRepository.java:95` (lado INSERT) usaba
  `Timestamp.from(generationTime.toInstant())` — también vulnerable
  a la zona horaria del servidor MariaDB si el driver no estaba
  configurado.
- `application.yml:14`: la URL JDBC NO tenía `connectionTimeZone`
  ni `serverTimezone`. El driver `mariadb-java-client` 3.x sin
  esta config negocia la zona con el servidor MariaDB y puede
  reinterpretar `TIMESTAMP` en función de `session_timezone`.

**Por qué TODAS las empresas fallaban tras reiniciar**: el `Instant`
absoluto guardado en BD se leía con una zona diferente a la usada al
insertar. El `OffsetDateTime` reconstruido al verificar tenía un
offset distinto al usado al hashear → el formato ISO con offset
(`yyyy-MM-dd'T'HH:mm:ssxxx`) cambiaba → el SHA-256 difería.

## Fix aplicado

### 1. `application.yml` — JDBC URL con `connectionTimeZone=UTC`

```yaml
url: ${BENJAGEST_DB_URL:jdbc:mariadb://localhost:3306/benjagest?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}
```

- Fuerza al driver a tratar `TIMESTAMP` como UTC siempre.
- `forceConnectionTimeZoneToSession=true` aplica también a la sesión
  MariaDB para que `CURRENT_TIMESTAMP` de la BD sea coherente.

### 2. `SifEventRepository.insert` — pasar OffsetDateTime UTC explícito

```java
OffsetDateTime utc = generationTime.withOffsetSameInstant(ZoneOffset.UTC);
// ... pasar `utc` al jdbcTemplate.update
```

El driver almacena el instante absoluto sin reinterpretar.

### 3. `SifEventRepository.mapChainRow` — leer como OffsetDateTime nativo

```java
OffsetDateTime gen;
try {
    gen = rs.getObject("generated_at", OffsetDateTime.class);
} catch (SQLException unsupported) {
    Timestamp ts = rs.getTimestamp("generated_at");
    gen = ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
}
```

Con `connectionTimeZone=UTC` el driver devuelve un `OffsetDateTime`
con offset `+00:00` y el mismo instante que se insertó. El fallback
sigue siendo seguro porque la sesión también es UTC.

## Cadenas ya existentes en BD

Los eventos **anteriores** al fix tienen su `hash_current` calculado
en condiciones inestables — pueden seguir reportándose como rotos.
Hay tres opciones:

1. **Esperar y observar**: con el fix, los eventos NUEVOS son
   coherentes. El job de anomalías seguirá reportando los antiguos.
2. **Truncar y arrancar de cero** (solo para entornos de desarrollo):
   `DELETE FROM sif_event_registry;` — pierdes el histórico de eventos.
   No usar en producción.
3. **Rebuild script** (sub-slice futuro): recalcular `hash_current`
   y `hash_previous` para todas las cadenas en empresas
   `verifactu_modality='NO_VERIFACTU'`. NO se puede aplicar a
   `VERIFACTU` real porque esas cadenas son legales y se han mandado
   firmadas al SEPE.

**Decisión actual (2026-06-08)**: opción 1. En desarrollo, todos los
testers están en NO_VERIFACTU; los warnings se ignoran. Cuando se
implemente el sub-slice de rebuild, se aplicará SOLO a
`NO_VERIFACTU` y dejará `VERIFACTU` intacto.

## Deuda técnica derivada

`VerifactuRegistryRepository` tiene el **mismo patrón** vulnerable
(líneas 163, 260-261, 369, 381, 398). Con el fix de URL ya queda
mitigado (la URL aplica a TODAS las queries del datasource). Si en
algún caso aparecen anomalías en la cadena de facturas, replicar
el cambio de `mapChainRow` / `insert` ahí también.

## Cómo verificar que el fix funciona

1. Tras pull + reiniciar backend.
2. Esperar 15 min (initialDelay del scheduler).
3. Mirar el log: el ciclo `VF-ANOMALY` debe terminar **sin** mensajes
   "cadena eventos SIF rota" para eventos NUEVOS (los antiguos
   pueden seguir reportándose hasta que se ejecute rebuild).
4. Inicia / detiene el backend varias veces y observa que NUEVOS
   eventos SYSTEM_START / SYSTEM_STOP NO se reportan como rotos en
   el siguiente ciclo.

## Pattern aplicado

Este fix es un caso ejemplar del pattern descrito en
`docs/agents-debug-pattern.md`. Tiempo total de diagnóstico: ~5 min
(dos agentes en paralelo) en lugar de horas de lectura secuencial.
Convergencia total: ambos agentes señalaron el mismo archivo:línea
por caminos diferentes.
