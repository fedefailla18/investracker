# Deployment Guide

Containerized deployment guidelines and environment configuration.

## Prerequisites

- Docker Engine 20.10+
- PostgreSQL 13+ instance
- Redis 7+ instance

## Container Build

The multi-stage `Dockerfile` compiles the executable jar and packages a minimal runtime image:

```bash
docker build -t investracker:latest .
```

## Production Environment Variables

| Variable | Description | Example / Recommendation |
|---|---|---|
| `SERVER_PORT` | HTTP application port | `9080` |
| `SPRING_DATASOURCE_URL` | PostgreSQL connection URL | `jdbc:postgresql://<host>:5432/<db>?currentSchema=file_importer_schema,public` |
| `SPRING_DATASOURCE_USERNAME` | Database username | Standard app user |
| `SPRING_DATASOURCE_PASSWORD` | Database password | Injected secret |
| `SPRING_DATA_REDIS_HOST` | Redis hostname | Cache cluster or host |
| `SPRING_DATA_REDIS_PORT` | Redis port | `6379` |
| `CRYPTOCOMPARE_API_KEY` | External price API key | Production key from CryptoCompare |
| `JWT_SECRET` | Token signing secret | Secure 256-bit key |
| `JWT_EXPIRATION` | Token TTL (ms) | `86400000` (24h) |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Hibernate schema validation | `validate` (Liquibase runs migrations) |

## Run Container

```bash
docker run -d \
  --name investracker \
  -p 9080:9080 \
  --env-file .env.production \
  investracker:latest
```

## Health Verification

- HTTP probe: `curl -f http://localhost:9080/swagger-ui.html`
- Container logs: `docker logs -f investracker`
