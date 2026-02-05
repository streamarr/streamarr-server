# Streamarr Server - Project Guidelines

## Engineering Philosophy

### TDD: Red-Green-Refactor
- Write a failing test FIRST (RED)
- Write the minimum code to make it pass (GREEN)
- Refactor with confidence (REFACTOR)
- Every feature and bug fix starts with a test
- When fixing a defect: first write an API-level failing test, then write the smallest test that replicates the problem, then get both to pass
- Refactor only when tests are green — never refactor while red
- Use the simplest solution that could possibly work

### Test Behavior, Not Implementation (Hexagonal Architecture)
- Test at the highest public API level — this means the **service layer**, not the protocol layer
- Service tests are protocol-agnostic: if we swap GraphQL for REST, these tests still pass
- GraphQL resolver tests are thin wiring tests only — verify delegation, not business logic
- Test observable behavior and outcomes, not internal method calls
- NEVER use mocks to verify interactions (no ArgumentCaptor, no verify())
- NEVER make a method public or package-private solely for testing
- NEVER break encapsulation for testability (no reflection like FieldUtils.writeField)
- Unit test pure logic in isolation only when it has meaningful complexity (parsers, pagination math)
- Integration tests with TestContainers + real PostgreSQL are the default for service-layer tests

### Tidy First (Kent Beck)
- Separate all changes into two types:
    1. STRUCTURAL: Rearranging code without changing behavior (renaming, extracting methods, moving code)
    2. BEHAVIORAL: Adding or modifying actual functionality
- Never mix structural and behavioral changes in the same commit
- Always make structural changes first when both are needed
- Validate structural changes don't alter behavior by running tests before and after

### Commit Discipline
- Only commit when ALL tests pass and ALL warnings are resolved
- Each commit is a single logical unit of work
- Commit messages state whether the change is structural or behavioral
- Small, frequent commits over large, infrequent ones
- Commit messages must be under 200 words
- Always use signed commits (`git commit -S`)
- NEVER include Co-Authored-By trailers
- Eliminate duplication ruthlessly; express intent through naming and structure

### SonarCloud Quality Gate
All PRs must pass these conditions on new code:
- **Coverage** ≥80% (aim for 90%) — write tests for new code
- **Duplicated Lines** ≤5% — extract shared logic, don't copy-paste
- **Maintainability Rating** A — no code smells
- **Reliability Rating** A — no bugs
- **Security Rating** A — no vulnerabilities
- **Security Hotspots Reviewed** 100% — review all flagged hotspots
- **New Lines** ≤2,000 — aim for ≤1,500 to leave buffer for test coverage

### Flat Control Flow
- Use early returns and guard clauses — avoid else/else-if chains
- No nested conditionals — extract to well-named private methods or use early exits
- Prefer switch expressions over if/else-if chains
- One level of indentation inside methods is ideal; two is acceptable; three means refactor

### Defensive Programming
- Fail fast with meaningful exceptions at system boundaries
- Use custom exceptions that convey intent (not generic RuntimeException)
- Validate inputs at the API boundary; trust internal code
- Use Optional for values that may be absent — never return null

### Code Style
- Google Java Format enforced via Spotless (runs on build)
- No manual formatting debates — the formatter is always right
- Concise over verbose — don't add comments for self-evident code
- Don't add javadoc/comments to code you didn't change

### Java Language
- Leverage Lombok: `@Slf4j`, `@Builder`, `@Getter`, `@RequiredArgsConstructor`, etc.
- Prefer Builders over passing args to constructors — use `@Builder` for domain objects, DTOs, and any class with more than 2-3 fields
- Prefer `var` for local variables unless the type isn't obvious or would lead to misinterpretation
- Use records for immutable data carriers (DTOs, value objects, embeddables)
- Use sealed interfaces/classes when the set of subtypes is known and fixed
- Prefer `Optional` over nullable returns — never return null from a public method
- Use `switch` expressions (not statements) with exhaustive pattern matching
- Prefer `Stream.toList()` over `Collectors.toList()` when an unmodifiable list is acceptable
- Use text blocks (`"""`) for multi-line strings (SQL, JSON, GraphQL)

## Architecture Rules
- Resolvers depend on Services; Services depend on Repositories; Domain depends on nothing
- Domain entities must NEVER import from services, repositories, or graphql packages
- Services must NEVER import from the graphql package
- External API types (TMDB DTOs) must NEVER leak into the service/domain layer
- These rules are enforced by ArchUnit tests

## Testing Conventions
- Integration tests: `*IT.java` suffix, `@Tag("IntegrationTest")`
- Unit tests: `*Test.java` suffix
- Always use `@DisplayName` on test methods for human-readable test output
- Use TestContainers for all database tests — no H2, no mocks for repositories
- Prefer "Fake" implementations (e.g., `FakeMovieRepository`) over mocks for collaborators
- Test naming: `shouldExpectedBehaviorWhenCondition()`

## Test Strategy (Hexagonal)
- **Service-layer integration tests** are the primary test type (e.g., MovieServiceIT)
    - Inject the real service, backed by a real PostgreSQL via TestContainers
    - Test business behavior: inputs → outputs, not internal wiring
    - Protocol-agnostic: these tests don't know GraphQL exists
- **GraphQL resolver tests** are thin wiring tests only
    - Verify resolvers delegate to services correctly
    - Do NOT test business logic at the protocol layer
- **Pure logic unit tests** for parsers, validators, and stateless utilities
    - No database, no Spring context, no mocks of collaborators
- **Anti-patterns to avoid:**
    - Mocking repositories/services to verify interactions (no ArgumentCaptor, no verify()) — use Fakes instead
    - Using reflection to set private fields (FieldUtils.writeField)
    - Testing implementation details that would break on refactoring

## Twelve-Factor Principles ([12factor.net](https://12factor.net))
We follow these factors from the Twelve-Factor App methodology:

- **III. Config** — All environment-specific config via environment variables, never hardcoded. Spring profiles for behavioral switches, env vars for secrets and connection strings.
- **IV. Backing Services** — PostgreSQL, TMDB API, OTel Collector are attached resources swappable via config. No code changes to point at a different database or metadata provider.
- **VI. Processes** — Application processes are stateless. In-memory session state (streaming sessions) is designed behind interfaces (`SegmentStore`, `TranscodeExecutor`) to allow externalization when scaling horizontally.
- **IX. Disposability** — Fast startup, graceful shutdown. FFmpeg processes shut down cleanly (write `q` to stdin → wait → `destroyForcibly()`). JVM shutdown hooks clean up temp directories.
- **X. Dev/Prod Parity** — TestContainers runs real PostgreSQL in tests. Docker Compose gives identical infrastructure locally and in production. No H2 or in-memory database substitutes.
- **XI. Logs** — Treat logs as event streams. No file-based logging. Structured output via OTel, consumed by the collector. Human-readable console output in dev.

## Tech Stack
- Java 25 (LTS), Spring Boot 4.0.2, PostgreSQL 17
- GraphQL via Netflix DGS 11.1.0
- jOOQ for complex queries, Flyway for migrations
- FFmpeg via ProcessBuilder for HLS transcoding
