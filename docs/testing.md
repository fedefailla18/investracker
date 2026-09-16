# Testing & Coverage Guide

Testing conventions, verification commands, and coverage targets.

## Test Commands

```bash
# Run unit tests (Spock specs in src/test/groovy)
./gradlew test

# Run a specific test class
./gradlew test --tests '*CoinInformationFacadeSpec'

# Run integration tests (Testcontainers Postgres in src/integration-test/groovy)
./gradlew integrationTest

# Run all tests and enforce coverage verification
./gradlew check

# Generate combined JaCoCo report
./gradlew jacocoAllTestReport
```

Report paths:
- Combined: `build/reports/jacoco/allTests/index.html`
- Unit: `build/reports/jacoco/test/html/index.html`
- Integration: `build/reports/jacoco/integrationTest/index.html`

## Verification Thresholds

- **Enforced Minimum**: 75% instruction coverage verified by `jacocoTestCoverageVerification` on `./gradlew check`.
- **Target Coverage**: 80% line, 70% branch.
- **Exclusions**: `com/importer/fileimporter/config/**`, `com/importer/fileimporter/dto/**`.

## Testing Stack & Conventions

- **Framework**: Spock 2.0 with Groovy 3.0 (`given:`, `when:`, `then:` blocks).
- **Unit Specs**: Extend `spock.lang.Specification`; isolate collaborators using `Mock()`.
- **Integration Specs**: Extend `BaseIntegrationSpec`; spins up a PostgreSQL 13.1 container via Testcontainers.

## Known Coverage Gaps

| Target Package | Classes | Priority Test Requirements |
|---|---|---|
| `config.security` / `controller.security` | `JwtAuthenticationFilter`, `JwtService`, `UserDetailsImpl`, `UserDetailsServiceImpl`, `AuthController` | JWT issuance/validation unit tests; `/api/auth/*` integration tests |
| `controller` | `HoldingController`, `PortfolioController`, `PricingController`, `WebController` | HTTP status code and response body integration specs |
| `service` | `CryptoCompareProxy`, `FileImporterService`, `PriceHistoryService`, `SymbolService` | Parsing and pricing error-path unit tests |
| `facade` | `PricingFacade` | Cache orchestration and fallback logic |
