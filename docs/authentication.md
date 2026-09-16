# Authentication

JWT-based authentication protecting all state-modifying and user-scoped endpoints.

## Seed Credentials (Local Development)

| Username | Email | Default Password |
|---|---|---|
| `default_user` | `default@example.com` | `change_me` |

## Authentication Endpoints

Endpoint contracts and schemas are defined in [`AuthController`](../src/main/java/com/importer/fileimporter/controller/security/AuthController.java) and [Swagger UI](http://localhost:9080/swagger-ui.html#/Authentication).

| Method | Endpoint | Description | Response Token Field |
|---|---|---|---|
| `POST` | `/api/auth/register` | Register new user account | - |
| `POST` | `/api/auth/login` | Authenticate and obtain JWT | `jwt` |

## Usage

Authenticate via `POST /api/auth/login`, extract the `jwt` field, and supply it as a Bearer token:

```bash
curl -H "Authorization: Bearer <JWT_TOKEN>" http://localhost:9080/holding
```

## Security Configuration

| Property | Default | Description |
|---|---|---|
| `jwt.secret` | *(Set in `application.yml`)* | HMAC-SHA256 signing secret key |
| `jwt.expiration` | `86400000` (24h) | Token validity period in milliseconds |
| Unauthenticated Routes | `/api/auth/**`, `/swagger-ui/**`, `/api-docs/**`, `/ws/**` | Permitted in `WebSecurityConfig` |
