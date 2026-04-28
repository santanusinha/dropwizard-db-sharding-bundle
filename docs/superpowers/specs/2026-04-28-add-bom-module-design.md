# Design: Add BOM Module for Hibernate 6

## Problem

`db-sharding-bundle` (Hibernate 6 line) depends on `hibernate-core:6.6.0.Final`, which
requires `jakarta.xml.bind-api:4.0.x` at runtime. However, the project's own
`dependencyManagement` imports `dropwizard-bom:2.1.12`, which pins
`jakarta.xml.bind-api` to `2.3.3` — a legacy transition release whose jar contains
`javax.xml.bind.*` classes instead of `jakarta.xml.bind.*`. This causes
`ClassNotFoundException: jakarta.xml.bind.JAXBException` at runtime for any consumer
that also imports the Dropwizard BOM (which is every Dropwizard app).

The fix must be owned by the bundle, not pushed onto each consumer.

## Approach

Convert the project to a multi-module Maven structure following the zeus project pattern.
The existing artifact keeps its coordinates unchanged. A new `db-sharding-bundle-parent`
module owns all shared dependency management. A new sibling `db-sharding-bundle-dependencies`
BOM module pins the full Hibernate 6 dependency stack, including the conflict-resolving
JAXB 4.x entries. Consumers get a consistent classpath by importing one BOM.

## Multi-Module Structure

```
dropwizard-db-sharding-bundle/          ← repo root
├── pom.xml                             ← ROOT: db-sharding-bundle-root (minimal aggregator)
├── db-sharding-bundle-parent/          ← new: all dependencyManagement + plugin management
│   └── pom.xml
├── db-sharding-bundle/                 ← existing code (same groupId + artifactId, unchanged)
│   ├── pom.xml
│   └── src/
└── db-sharding-bundle-dependencies/    ← new BOM module
    └── pom.xml
```

### Root POM (`db-sharding-bundle-root`)

- `groupId`: `io.appform.dropwizard.sharding`
- `artifactId`: `db-sharding-bundle-root`
- `packaging`: `pom`
- Owns: `<properties>` (version variables), `<scm>`, `<licenses>`, `<developers>`,
  `<distributionManagement>`, `<repositories>`, release profile, `<modules>`
- **No `<dependencyManagement>`** — intentionally empty so that child modules which
  inherit from root (i.e. `db-sharding-bundle-dependencies`) do not pick up any
  transitive BOM pins

### `db-sharding-bundle-parent`

- `groupId`: `io.appform.dropwizard.sharding`
- `artifactId`: `db-sharding-bundle-parent`
- `packaging`: `pom`
- `<parent>`: `db-sharding-bundle-root`
- Owns: `<dependencyManagement>` BOM imports (`dropwizard-bom`, `junit-bom`),
  `<build><pluginManagement>` (compiler with lombok, versions plugin),
  `<build><plugins>` (enforcer, nexus-staging)

### `db-sharding-bundle` child

- `groupId`: `io.appform.dropwizard.sharding` (unchanged)
- `artifactId`: `db-sharding-bundle` (unchanged)
- `packaging`: `jar`
- `<parent>`: `db-sharding-bundle-parent`
- Owns: only its `<dependencies>` and module-specific `<build>` plugins
  (surefire, jacoco, source, javadoc)
- **No client changes required** — Maven coordinates are identical to the pre-refactor artifact

### `db-sharding-bundle-dependencies`

- `groupId`: `io.appform.dropwizard.sharding`
- `artifactId`: `db-sharding-bundle-dependencies`
- `packaging`: `pom`
- `<parent>`: `db-sharding-bundle-root` — safe because root has **no** `<dependencyManagement>`,
  so consumers importing this BOM receive only its own explicit entries with no transitive
  BOM pins (no BOM hell). If it inherited from `db-sharding-bundle-parent`, Maven would
  merge `dropwizard-bom` into every consumer's effective dependency management.
- All versions hardcoded (no `${property}` references needed)
- Contains only `<dependencyManagement>` — no `<dependencies>`, no source

## BOM Dependency Management Contents

### Hibernate 6 ORM stack
| Artifact | Version |
|---|---|
| `org.hibernate.orm:hibernate-core` | `6.6.0.Final` |
| `org.hibernate.orm:hibernate-envers` | `6.6.0.Final` |
| `org.hibernate.validator:hibernate-validator` | `6.2.5.Final` |
| `com.fasterxml.jackson.datatype:jackson-datatype-hibernate6` | `2.16.1` |
| `com.github.phaneesh:jasypt-hibernate6` | `1.9.6` |

### Transitive conflict overrides (defeats Dropwizard BOM pins)
| Artifact | Version | Overrides |
|---|---|---|
| `jakarta.xml.bind:jakarta.xml.bind-api` | `4.0.2` | DW BOM pins `2.3.3` (wrong namespace) |
| `org.glassfish.jaxb:jaxb-runtime` | `4.0.2` | JAXB runtime impl, must match API version |
| `jakarta.activation:jakarta.activation-api` | `2.1.3` | Required by JAXB 4.x |

## Versioning

All three modules share version `2.1.12-HIBERNATE6-RC2` via `${project.version}`.
The RC is bumped from RC1 because this is a structural release. Future version changes
use `mvn versions:set -DnewVersion=<ver>` which updates all three in one command.

## Consumer Migration

Consumers using only the jar need **no changes**:
```xml
<dependency>
    <groupId>io.appform.dropwizard.sharding</groupId>
    <artifactId>db-sharding-bundle</artifactId>
    <version>2.1.12-HIBERNATE6-RC2</version>
</dependency>
```

Consumers who want correct Hibernate 6 transitive versions import the BOM. It **must
be imported before `dropwizard-bom`** because Maven resolves BOM imports in declaration
order (first entry wins for any given artifact):

```xml
<dependencyManagement>
    <dependencies>
        <!-- Import BEFORE dropwizard-bom so Hibernate 6 versions win -->
        <dependency>
            <groupId>io.appform.dropwizard.sharding</groupId>
            <artifactId>db-sharding-bundle-dependencies</artifactId>
            <version>2.1.12-HIBERNATE6-RC2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>io.dropwizard</groupId>
            <artifactId>dropwizard-bom</artifactId>
            <version>2.1.12</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Branch

`add-bom` branched from `hibernate6-new`.
