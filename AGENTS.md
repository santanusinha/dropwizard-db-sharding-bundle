# AGENTS.md - Development Guide for dropwizard-db-sharding-bundle

## Project Overview
This is a Dropwizard bundle providing application-level database sharding over relational databases (SQL). It uses Hibernate as the ORM layer and supports consistent hashing for shard distribution.

## Build Commands

### Full Build with Tests
```bash
mvn clean verify
```

### Run Tests Only
```bash
mvn test
```

### Run a Single Test Class
```bash
mvn test -Dtest=RelationalDaoTest
```

### Run a Specific Test Method
```bash
mvn test -Dtest=RelationalDaoTest#testBulkSave
```

### Run Tests with Maven Surefire (parallel execution supported)
```bash
mvn surefire:test
```

### Compile Only
```bash
mvn compile
```

### Package
```bash
mvn package -DskipTests
```

### Skip Tests During Build
```bash
mvn clean install -DskipTests
```

### Run with Code Coverage (Jacoco)
```bash
mvn clean verify  # Jacoco runs automatically during verify phase
```

---

## Code Style Guidelines

### License Header
Every Java file must include the Apache License 2.0 header:
```java
/*
 * Copyright [year] [author] <[email]>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
```

### Package Structure
- Base package: `io.appform.dropwizard.sharding`
- Package naming follows dot-separated modules:
  - `dao` - Data access objects
  - `sharding` - Sharding logic and implementations
  - `config` - Configuration classes
  - `execution` - Transaction execution utilities
  - `caching` - Cache interfaces and implementations
  - `scroll` - Scroll/pagination support
  - `metrics` - Metrics and monitoring
  - `observers` - Transaction observers
  - `healthcheck` - Health check utilities
  - `exceptions` - Custom exceptions
  - `admin` - Admin tasks

### Imports
- Group imports in this order:
  1. `io.appform.*` internal packages
  2. Third-party libraries (e.g., `lombok.*`, `org.hibernate.*`)
  3. Standard library (`java.*`)
  4. JUnit/test imports (`org.junit.jupiter.*`, `org.mockito.*`)
- Use wildcard imports sparingly (`java.util.*` is acceptable)

### Lombok Usage
The project uses Lombok extensively. Common annotations:
- `@Slf4j` - Logger injection
- `@Getter` / `@Setter` - Generate getters/setters
- `@Builder` - Builder pattern generation
- `@NoArgsConstructor` / `@AllArgsConstructor` - Constructors
- `@NonNull` - Null validation on parameters
- `@SneakyThrows` - Suppress checked exceptions (use sparingly)
- Use `lombok.val` for local variable type inference in tests

Example:
```java
@Slf4j
@Getter
public class MyClass {
    @NonNull private final String field;
    
    public void method() {
        val result = compute(); // In tests only
    }
}
```

### Naming Conventions
- **Classes**: PascalCase (`RelationalDao`, `ShardManager`)
- **Methods**: camelCase (`saveEntity`, `getShardCalculator`)
- **Constants**: UPPER_SNAKE_CASE (`MAX_SHARD_COUNT`)
- **Test classes**: Append `Test` suffix (`RelationalDaoTest`)
- **Test methods**: Use `test` prefix or descriptive names (`testBulkSave`, `testCreateOrUpdate`)
- **Package-private inner classes**: PascalCase (`ReadOnlyContext`, `AssociationMappingSpec`)
- **Abstract classes**: Append `Base` suffix (`DBShardingBundleBase`)

### Formatting
- 4-space indentation (no tabs)
- Line length: ~120 characters max (soft guideline)
- Opening brace on same line for classes/methods
- Single space before opening parenthesis in method declarations
- Use final modifier for parameters and local variables where appropriate
- Prefer composition over inheritance

### Type System
- Target Java 17 with source/target compatibility for Java 11
- Use generics appropriately; avoid raw types in new code
- Use `Optional<T>` for nullable return types
- Use `List`, `Map`, `Set` interfaces over concrete implementations in method signatures
- Use `Function<T, R>`, `Supplier<T>`, `Consumer<T>` for functional parameters

### Error Handling
- Use checked exceptions sparingly; prefer runtime exceptions
- Catch specific exceptions rather than generic `Exception`
- Wrap exceptions with context where meaningful
- Custom exceptions extend `RuntimeException` or specific existing exceptions
- Methods that can throw exceptions should declare `throws Exception` in signature
- Use `@SneakyThrows` only for library method calls (not business logic)

### Transactions
- All database operations must occur within a transaction context
- Use `TransactionExecutor` for programmatic transaction management
- Use `LockedContext` for pessimistic locking operations
- Read-only operations should use read-only transactions

### JUnit 5 Test Conventions
- Use JUnit Jupiter (`org.junit.jupiter.api.*`)
- Use `@BeforeEach` and `@AfterEach` for setup/teardown
- Use `@Test` annotations on test methods
- Use static imports for assertions: `import static org.junit.jupiter.api.Assertions.*`
- Test naming: `testMethodName_Scenario_ExpectedBehavior()`
- Group related assertions in single test method
- Use `Assertions.assertEquals`, `Assertions.assertNotNull`, etc.

### Mockito Conventions
- Mock dependencies explicitly
- Use `Mockito.when()` for stubbing
- Use `Mockito.verify()` for assertions on mock interactions
- Use `ArgumentMatchers.*` for flexible argument matching

### Hibernate/JPA Conventions
- Entities use JPA annotations (`@Entity`, `@Table`, `@Id`, `@Column`)
- Use `@NamedQuery` for complex frequently-used queries
- Use `DetachedCriteria` for dynamic queries
- Annotate lookup fields with `@LookupKey` for `LookupDao`
- Use `@Transaction` attribute for transaction demarcation

### Entity Conventions
- Override `equals()` and `hashCode()` properly
- Use `Hibernate.getClass()` for proper class comparison
- Use `@Builder` for entity construction
- Store entities with `@Getter/@Setter` for minimal boilerplate

### Documentation
- Add Javadoc for public APIs
- Include `@param`, `@return`, `@throws` tags where meaningful
- Keep Javadoc concise but informative
- Document complex business logic with comments

### Logging
- Use SLF4J with `@Slf4j` annotation
- Use appropriate log levels:
  - `log.debug()` for development details
  - `log.info()` for significant events
  - `log.warn()` for recoverable issues
  - `log.error()` for failures
- Avoid logging sensitive data

### Code Review Checklist
- [ ] License header present
- [ ] Lombok annotations used appropriately
- [ ] Null handling considered
- [ ] Transactions properly managed
- [ ] Tests cover happy path and edge cases
- [ ] No unnecessary warnings (`@SuppressWarnings` if needed)
- [ ] Logging appropriate
- [ ] Code compiles without errors

---

## Project Dependencies
- **Dropwizard**: 2.1.12 (provided)
- **Hibernate**: Via dropwizard-dependencies
- **Lombok**: 1.18.42 (annotation processor)
- **CGLIB**: 3.3.0
- **Caffeine**: 2.9.3
- **Jasypt**: 1.9.5 (password encryption)
- **H2**: 2.2.224 (test only)
- **JUnit Jupiter**: 5.9.3
- **Mockito**: 4.3.1

## Key Interfaces
- `ShardedDao<T>` - Base interface for all DAOs
- `RelationalDao<T>` - For parent-child entity relationships
- `LookupDao<T>` - For top-level entity lookup
- `CacheableLookupDao<T>` - Cached lookup operations
- `CacheableRelationalDao<T>` - Cached relational operations
- `ShardManager` - Shard management and routing
- `TransactionObserver` - Transaction lifecycle hooks
