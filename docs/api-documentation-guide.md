# API Documentation Guide

Standards for documenting REST endpoints in InvestTracker using SpringDoc OpenAPI 3.0.

## Endpoints

- **Swagger UI**: `http://localhost:9080/swagger-ui.html`
- **OpenAPI Schema**: `http://localhost:9080/api-docs`
- **Reference Implementation**: [`TransactionController`](../src/main/java/com/importer/fileimporter/controller/TransactionController.java)

## Required Annotations

| Annotation | Target | Standard |
|---|---|---|
| `@Tag` | Controller class | Group endpoints by domain entity (`name`, `description`). |
| `@Operation` | Method | Active voice `summary` (< 60 chars) and functional `description`. |
| `@ApiResponse` | Method | Explicit status codes (`200`/`201`, `400`, `401`, `404`, `500`) with DTO `@Schema`. |
| `@Parameter` | Query / Path param | Param description and requirement flag. |
| `@Schema` | DTO fields | Data constraints, format, and description. |

## Documentation Rules

- Document all terminal responses, including failure modes (`401 Unauthorized`, `404 Not Found`).
- Do not duplicate field types in descriptions; use Jackson annotations and Bean Validation (`@NotNull`, `@Size`).
- Verify endpoint schemas locally via Swagger UI before opening PRs.
