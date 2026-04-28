# Add BOM Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert `db-sharding-bundle` into a 4-module Maven project (zeus-style), adding `db-sharding-bundle-dependencies` that pins the full Hibernate 6 dependency stack so consumers get correct transitive versions by importing one BOM.

**Architecture:** Root POM (`db-sharding-bundle-root`) is a minimal aggregator with only properties and metadata — no `<dependencyManagement>`. A new `db-sharding-bundle-parent` child owns BOM imports and plugin management. The existing JAR source moves into `db-sharding-bundle/` (coordinates unchanged). `db-sharding-bundle-dependencies` inherits from root (safe — root has no BOM imports), contains only hardcoded Hibernate 6 + JAXB 4 version pins.

**Tech Stack:** Maven multi-module, Hibernate ORM 6.6.0.Final, Jakarta XML Bind API 4.0.2, JUnit 5.

---

## File Map

| Action | Path | Purpose |
|---|---|---|
| Modify | `pom.xml` | Becomes `db-sharding-bundle-root` (minimal aggregator, no dependencyManagement) |
| Create | `db-sharding-bundle-parent/pom.xml` | Owns dependencyManagement BOM imports + pluginManagement |
| Create | `db-sharding-bundle/pom.xml` | Child JAR module — owns `<dependencies>` and jar-specific plugins |
| Move | `src/` → `db-sharding-bundle/src/` | All existing source and tests |
| Move | `lombok.config` → `db-sharding-bundle/lombok.config` | Lombok config is module-scoped |
| Create | `db-sharding-bundle-dependencies/pom.xml` | BOM — owns only `<dependencyManagement>`, parent = root |
| Modify | `README.md` | Add Hibernate 6 + BOM usage section |

---

## Task 1: Create branch and scaffold directories

**Files:** No file changes — git and filesystem setup only.

- [ ] **Step 1: Create the working branch**

```bash
cd ~/Downloads/phonepe/dropwizard-db-sharding-bundle
git checkout hibernate6-new
git pull
git checkout -b add-bom
```

Expected: `Switched to a new branch 'add-bom'`

- [ ] **Step 2: Create sub-module directories**

```bash
mkdir -p db-sharding-bundle/src
mkdir -p db-sharding-bundle-parent
mkdir -p db-sharding-bundle-dependencies
```

---

## Task 2: Move source into the child module directory

**Files:**
- Move: `src/main` → `db-sharding-bundle/src/main`
- Move: `src/test` → `db-sharding-bundle/src/test`
- Move: `lombok.config` → `db-sharding-bundle/lombok.config`

- [ ] **Step 1: Move source tree**

```bash
cd ~/Downloads/phonepe/dropwizard-db-sharding-bundle
git mv src/main db-sharding-bundle/src/main
git mv src/test db-sharding-bundle/src/test
```

- [ ] **Step 2: Move lombok config**

```bash
git mv lombok.config db-sharding-bundle/lombok.config
```

- [ ] **Step 3: Verify the move**

```bash
ls db-sharding-bundle/src/
```

Expected:
```
main  test
```

---

## Task 3: Transform root `pom.xml` into `db-sharding-bundle-root`

**Files:**
- Modify: `pom.xml`

Root becomes a minimal aggregator. It keeps metadata (scm, licenses, developers, distributionManagement, repositories, properties, release profile) but has **no `<dependencyManagement>`** and **no `<dependencies>`**. That is the key — any module that inherits from this root gets zero BOM pollution.

- [ ] **Step 1: Replace root `pom.xml` entirely**

Replace the full content of `pom.xml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.appform.dropwizard.sharding</groupId>
    <artifactId>db-sharding-bundle-root</artifactId>
    <version>2.1.12-HIBERNATE6-RC2</version>
    <packaging>pom</packaging>
    <name>Dropwizard Database Sharding Bundle Root</name>
    <url>https://github.com/santanusinha/dropwizard-db-sharding-bundle</url>
    <description>Application layer database sharding over SQL dbs</description>
    <inceptionYear>2016</inceptionYear>

    <modules>
        <module>db-sharding-bundle-parent</module>
        <module>db-sharding-bundle</module>
        <module>db-sharding-bundle-dependencies</module>
    </modules>

    <scm>
        <connection>scm:git:https://github.com/santanusinha/dropwizard-db-sharding-bundle</connection>
        <developerConnection>scm:git:https://github.com/santanusinha/dropwizard-db-sharding-bundle</developerConnection>
        <tag>HEAD</tag>
        <url>https://github.com/santanusinha/dropwizard-db-sharding-bundle</url>
    </scm>

    <licenses>
        <license>
            <name>The Apache Software License, Version 2.0</name>
            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
            <distribution>repo</distribution>
            <comments>A business-friendly OSS license</comments>
        </license>
    </licenses>

    <developers>
        <developer>
            <id>santanusinha</id>
            <name>Santanu Sinha</name>
            <email>santanu.sinha@gmail.com</email>
            <roles>
                <role>owner</role>
                <role>developer</role>
            </roles>
        </developer>
        <developer>
            <id>AnkushNakaskar</id>
            <name>Ankush Nakaskar</name>
            <email>ankush.nakaskar@gmail.com</email>
            <roles>
                <role>developer</role>
            </roles>
        </developer>
        <developer>
            <id>RishabhGoyal</id>
            <name>Rishabh Goyal</name>
            <email>rgoyal2191@gmail.com</email>
            <roles>
                <role>developer</role>
            </roles>
        </developer>
    </developers>

    <issueManagement>
        <system>GitHub Issues</system>
        <url>https://github.com/santanusinha/dropwizard-db-sharding-bundle/issues</url>
    </issueManagement>

    <ciManagement>
        <system>Travis CI</system>
        <url>https://travis-ci.org/santanusinha/dropwizard-db-sharding-bundle</url>
    </ciManagement>

    <distributionManagement>
        <snapshotRepository>
            <id>ossrh</id>
            <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        </snapshotRepository>
        <repository>
            <id>ossrh</id>
            <url>https://ossrh-staging-api.central.sonatype.com/</url>
        </repository>
    </distributionManagement>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <dropwizard.version>2.1.12</dropwizard.version>

        <hibernate.version>6.6.0.Final</hibernate.version>
        <hibernate-validator.version>6.2.5.Final</hibernate-validator.version>
        <jackson-datatype-hibernate6.version>2.16.1</jackson-datatype-hibernate6.version>
        <jasypt-hibernate6.version>1.9.6</jasypt-hibernate6.version>

        <lombok.version>1.18.42</lombok.version>
        <cglib.version>3.3.0</cglib.version>
        <caffeine.version>2.9.3</caffeine.version>
        <junit.jupiter.version>5.9.3</junit.jupiter.version>
        <h2.version>2.2.224</h2.version>
        <mockito.version>4.3.1</mockito.version>
    </properties>

    <repositories>
        <repository>
            <id>clojars.org</id>
            <url>https://clojars.org/repo</url>
        </repository>
    </repositories>

    <profiles>
        <profile>
            <id>release</id>
            <activation>
                <property>
                    <name>release</name>
                    <value>true</value>
                </property>
            </activation>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-gpg-plugin</artifactId>
                        <version>1.6</version>
                        <executions>
                            <execution>
                                <id>sign-artifacts</id>
                                <phase>verify</phase>
                                <goals>
                                    <goal>sign</goal>
                                </goals>
                                <configuration>
                                    <useAgent>true</useAgent>
                                    <executable>gpg2</executable>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>

</project>
```

---

## Task 4: Create `db-sharding-bundle-parent/pom.xml`

**Files:**
- Create: `db-sharding-bundle-parent/pom.xml`

This module owns the heavy `<dependencyManagement>` (BOM imports for dropwizard and JUnit), `<pluginManagement>` (compiler with lombok, versions plugin), and the shared `<plugins>` (enforcer, nexus-staging). The JAR module inherits from here.

- [ ] **Step 1: Create the parent POM**

Create `db-sharding-bundle-parent/pom.xml` with this exact content:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.appform.dropwizard.sharding</groupId>
        <artifactId>db-sharding-bundle-root</artifactId>
        <version>2.1.12-HIBERNATE6-RC2</version>
    </parent>

    <artifactId>db-sharding-bundle-parent</artifactId>
    <packaging>pom</packaging>
    <name>Dropwizard Database Sharding Bundle Parent</name>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.dropwizard</groupId>
                <artifactId>dropwizard-bom</artifactId>
                <type>pom</type>
                <scope>import</scope>
                <version>${dropwizard.version}</version>
            </dependency>
            <dependency>
                <groupId>org.junit</groupId>
                <artifactId>junit-bom</artifactId>
                <version>${junit.jupiter.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>versions-maven-plugin</artifactId>
                    <version>2.5</version>
                    <configuration>
                        <generateBackupPoms>false</generateBackupPoms>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.8.1</version>
                    <configuration>
                        <source>17</source>
                        <target>17</target>
                        <release>11</release>
                        <annotationProcessors>
                            <annotationProcessor>lombok.launch.AnnotationProcessorHider$AnnotationProcessor</annotationProcessor>
                        </annotationProcessors>
                        <annotationProcessorPaths>
                            <annotationProcessorPath>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                                <version>${lombok.version}</version>
                            </annotationProcessorPath>
                        </annotationProcessorPaths>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-enforcer-plugin</artifactId>
                <version>3.0.0-M2</version>
                <executions>
                    <execution>
                        <id>enforce-no-snapshots</id>
                        <goals>
                            <goal>enforce</goal>
                        </goals>
                        <configuration>
                            <rules>
                                <requireReleaseDeps>
                                    <message>No Snapshots Allowed!</message>
                                </requireReleaseDeps>
                            </rules>
                            <fail>true</fail>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.sonatype.plugins</groupId>
                <artifactId>nexus-staging-maven-plugin</artifactId>
                <version>1.6.13</version>
                <extensions>true</extensions>
                <configuration>
                    <serverId>ossrh</serverId>
                    <nexusUrl>https://ossrh-staging-api.central.sonatype.com/</nexusUrl>
                    <autoReleaseAfterClose>true</autoReleaseAfterClose>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
```

---

## Task 5: Create `db-sharding-bundle/pom.xml` (JAR child module)

**Files:**
- Create: `db-sharding-bundle/pom.xml`

This module owns all `<dependencies>` and the jar-specific build plugins (surefire, jacoco, source, javadoc). It inherits properties and dependencyManagement from `db-sharding-bundle-parent`.

- [ ] **Step 1: Create the child POM**

Create `db-sharding-bundle/pom.xml` with this exact content:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.appform.dropwizard.sharding</groupId>
        <artifactId>db-sharding-bundle-parent</artifactId>
        <version>2.1.12-HIBERNATE6-RC2</version>
        <relativePath>../db-sharding-bundle-parent/pom.xml</relativePath>
    </parent>

    <artifactId>db-sharding-bundle</artifactId>
    <name>Dropwizard Database Sharding Bundle</name>

    <dependencies>
        <dependency>
            <groupId>commons-beanutils</groupId>
            <artifactId>commons-beanutils</artifactId>
            <version>1.9.4</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-engine</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.dropwizard</groupId>
            <artifactId>dropwizard-core</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.dropwizard</groupId>
            <artifactId>dropwizard-db</artifactId>
        </dependency>
        <dependency>
            <groupId>org.hibernate.orm</groupId>
            <artifactId>hibernate-core</artifactId>
            <version>${hibernate.version}</version>
        </dependency>
        <dependency>
            <groupId>org.hibernate.orm</groupId>
            <artifactId>hibernate-envers</artifactId>
            <version>${hibernate.version}</version>
        </dependency>
        <dependency>
            <groupId>org.hibernate.validator</groupId>
            <artifactId>hibernate-validator</artifactId>
            <version>${hibernate-validator.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-hibernate6</artifactId>
            <version>${jackson-datatype-hibernate6.version}</version>
        </dependency>
        <dependency>
            <groupId>com.github.phaneesh</groupId>
            <artifactId>jasypt-hibernate6</artifactId>
            <version>${jasypt-hibernate6.version}</version>
        </dependency>
        <dependency>
            <groupId>cglib</groupId>
            <artifactId>cglib-nodep</artifactId>
            <version>${cglib.version}</version>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
            <version>${caffeine.version}</version>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <version>${h2.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.dropwizard</groupId>
            <artifactId>dropwizard-testing</artifactId>
            <scope>test</scope>
            <exclusions>
                <exclusion>
                    <groupId>junit</groupId>
                    <artifactId>junit</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>com.fasterxml.jackson.module</groupId>
                    <artifactId>*</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.reflections</groupId>
            <artifactId>reflections</artifactId>
            <version>0.10.2</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <argLine>
                        --add-opens java.base/java.lang=ALL-UNNAMED
                    </argLine>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.12</version>
                <executions>
                    <execution>
                        <id>prepare-agent</id>
                        <goals>
                            <goal>prepare-agent</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>prepare-package</phase>
                        <goals>
                            <goal>report</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-source-plugin</artifactId>
                <version>3.3.0</version>
                <executions>
                    <execution>
                        <id>attach-sources</id>
                        <goals>
                            <goal>jar</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-javadoc-plugin</artifactId>
                <version>3.6.3</version>
                <configuration>
                    <source>17</source>
                </configuration>
                <executions>
                    <execution>
                        <id>attach-javadocs</id>
                        <goals>
                            <goal>jar</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

</project>
```

---

## Task 6: Create `db-sharding-bundle-dependencies/pom.xml`

**Files:**
- Create: `db-sharding-bundle-dependencies/pom.xml`

The BOM inherits from the ROOT (`db-sharding-bundle-root`). Root is safe to inherit from — it has no `<dependencyManagement>`, so consumers importing this BOM receive only the entries explicitly declared here. All versions are hardcoded (not property references) for a self-documenting, auditable version contract.

- [ ] **Step 1: Create the BOM POM**

Create `db-sharding-bundle-dependencies/pom.xml` with this exact content:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.appform.dropwizard.sharding</groupId>
        <artifactId>db-sharding-bundle-root</artifactId>
        <version>2.1.12-HIBERNATE6-RC2</version>
    </parent>

    <artifactId>db-sharding-bundle-dependencies</artifactId>
    <packaging>pom</packaging>
    <name>Dropwizard Database Sharding Bundle Dependencies</name>
    <description>Dependency management for Dropwizard DB Sharding Bundle — Hibernate 6 edition.
        Inherits from db-sharding-bundle-root which has no dependencyManagement, so consumers
        who import this BOM receive only these explicit entries with no transitive BOM pollution.
        Import BEFORE dropwizard-bom so Hibernate 6 versions win.</description>

    <dependencyManagement>
        <dependencies>

            <!-- ===== Hibernate 6 ORM stack ===== -->
            <dependency>
                <groupId>org.hibernate.orm</groupId>
                <artifactId>hibernate-core</artifactId>
                <version>6.6.0.Final</version>
            </dependency>
            <dependency>
                <groupId>org.hibernate.orm</groupId>
                <artifactId>hibernate-envers</artifactId>
                <version>6.6.0.Final</version>
            </dependency>
            <dependency>
                <groupId>org.hibernate.validator</groupId>
                <artifactId>hibernate-validator</artifactId>
                <version>6.2.5.Final</version>
            </dependency>
            <dependency>
                <groupId>com.fasterxml.jackson.datatype</groupId>
                <artifactId>jackson-datatype-hibernate6</artifactId>
                <version>2.16.1</version>
            </dependency>
            <dependency>
                <groupId>com.github.phaneesh</groupId>
                <artifactId>jasypt-hibernate6</artifactId>
                <version>1.9.6</version>
            </dependency>

            <!-- ===== JAXB 4.x stack =====
                 Overrides dropwizard-bom's jakarta.xml.bind-api:2.3.3 pin (a legacy
                 transition release whose jar contains javax.xml.bind.* classes, not
                 jakarta.xml.bind.*). Hibernate 6 requires jakarta.xml.bind-api 4.x.
                 IMPORTANT: import this BOM BEFORE dropwizard-bom so these entries win. -->
            <dependency>
                <groupId>jakarta.xml.bind</groupId>
                <artifactId>jakarta.xml.bind-api</artifactId>
                <version>4.0.2</version>
            </dependency>
            <dependency>
                <groupId>org.glassfish.jaxb</groupId>
                <artifactId>jaxb-runtime</artifactId>
                <version>4.0.2</version>
            </dependency>
            <dependency>
                <groupId>jakarta.activation</groupId>
                <artifactId>jakarta.activation-api</artifactId>
                <version>2.1.3</version>
            </dependency>

        </dependencies>
    </dependencyManagement>

</project>
```

---

## Task 7: Build and verify

**Files:** No changes — verification only.

- [ ] **Step 1: Build the full project from the root**

```bash
cd ~/Downloads/phonepe/dropwizard-db-sharding-bundle
mvn clean install -DskipTests
```

Expected: `BUILD SUCCESS` with four artifacts:
```
[INFO] Dropwizard Database Sharding Bundle Root ............. SUCCESS
[INFO] Dropwizard Database Sharding Bundle Parent ........... SUCCESS
[INFO] Dropwizard Database Sharding Bundle .................. SUCCESS
[INFO] Dropwizard Database Sharding Bundle Dependencies ..... SUCCESS
```

If you see `Could not find artifact` errors, verify that the `<modules>` in root `pom.xml`
lists `db-sharding-bundle-parent`, `db-sharding-bundle`, and `db-sharding-bundle-dependencies`
and that `db-sharding-bundle/pom.xml` has the correct `<relativePath>` to the parent.

- [ ] **Step 2: Run the tests**

```bash
mvn test -pl db-sharding-bundle
```

Expected: `BUILD SUCCESS` with same test count as before the restructure.

- [ ] **Step 3: Verify BOM is installed in local repo**

```bash
find ~/.m2/repository/io/appform/dropwizard/sharding/db-sharding-bundle-dependencies \
     -name "*.pom" | head -3
```

Expected: a `.pom` at path ending in `2.1.12-HIBERNATE6-RC2/db-sharding-bundle-dependencies-2.1.12-HIBERNATE6-RC2.pom`

- [ ] **Step 4: Verify BOM has no transitive dependencyManagement from dropwizard-bom**

```bash
mvn help:effective-pom -pl db-sharding-bundle-dependencies | \
    grep -A2 "dropwizard\|jersey\|jakarta.ws"
```

Expected: **no output** — no Dropwizard or Jersey artifacts in the BOM's effective `<dependencyManagement>`.

- [ ] **Step 5: Commit the restructure**

```bash
cd ~/Downloads/phonepe/dropwizard-db-sharding-bundle
git add pom.xml db-sharding-bundle-parent/ db-sharding-bundle/ db-sharding-bundle-dependencies/
git commit -m "refactor: convert to multi-module structure with db-sharding-bundle-root

- Root POM (db-sharding-bundle-root): minimal aggregator, properties + metadata only,
  no dependencyManagement to prevent BOM contamination in child POMs
- db-sharding-bundle-parent: owns dropwizard-bom + junit-bom imports, pluginManagement
- db-sharding-bundle: JAR module with same Maven coordinates as before (no client changes)
- db-sharding-bundle-dependencies: BOM pinning full Hibernate 6 + JAXB 4.x stack,
  inherits from root (safe: root has no dependencyManagement)

Fixes: ClassNotFoundException: jakarta.xml.bind.JAXBException caused by
dropwizard-bom pinning jakarta.xml.bind-api:2.3.3 (javax namespace) over
Hibernate 6's required 4.0.x (jakarta namespace).

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 8: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace the Usage section**

Find the existing `## Usage` section and replace it with:

```markdown
## Usage

### Hibernate 6 (current)

Add the bundle dependency:

\`\`\`xml
<dependency>
    <groupId>io.appform.dropwizard.sharding</groupId>
    <artifactId>db-sharding-bundle</artifactId>
    <version>2.1.12-HIBERNATE6-RC2</version>
</dependency>
\`\`\`

To get a consistent Hibernate 6 dependency stack (and fix the `jakarta.xml.bind-api`
version conflict with `dropwizard-bom`), also import the BOM. It **must come before
`dropwizard-bom`** in your `dependencyManagement` — Maven resolves BOM imports in
declaration order and the first entry for any given artifact wins:

\`\`\`xml
<dependencyManagement>
    <dependencies>
        <!-- BEFORE dropwizard-bom so Hibernate 6 versions win -->
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
\`\`\`

The BOM pins:
- `org.hibernate.orm:hibernate-core` + `hibernate-envers` — `6.6.0.Final`
- `org.hibernate.validator:hibernate-validator` — `6.2.5.Final`
- `com.fasterxml.jackson.datatype:jackson-datatype-hibernate6` — `2.16.1`
- `jakarta.xml.bind:jakarta.xml.bind-api` — `4.0.2` *(overrides dropwizard-bom's `2.3.3`)*
- `org.glassfish.jaxb:jaxb-runtime` — `4.0.2`
- `jakarta.activation:jakarta.activation-api` — `2.1.3`
```

- [ ] **Step 2: Commit the README update**

```bash
git add README.md
git commit -m "docs: add Hibernate 6 + BOM usage section to README

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Version bumps (future reference)

To bump all modules together from the repo root:

```bash
mvn versions:set -DnewVersion=<new-version> -DprocessAllModules=true
git add -A && git commit -m "chore: bump version to <new-version>"
```
