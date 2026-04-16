# ECR Deployment Guide

The app runs in Docker on an AWS Lightsail instance, pulled from ECR. Two scripts handle the full lifecycle:

| Script | When to run |
|--------|-------------|
| `setup_server.sh` | Once — after first deploy or after the server is rebuilt |
| `deploy_update.sh` | Every time a new image is pushed to ECR |

---

## Prerequisites

### Local machine
- Docker with `linux/amd64` build support (Docker Desktop on Windows/Mac includes this)
- AWS CLI configured with two profiles:
  - Default profile — used for `ecr:GetAuthorizationToken` during push
  - `ecr-puller` profile — used on the server for pull authentication

### Lightsail server
- Docker + Docker Compose installed
- AWS CLI configured with the `ecr-puller` profile (`~/.aws/credentials`)
- `docker-compose.prod.yml` in `/home/bitnami/` (see structure below)
- `config/application.properties` in `/home/bitnami/config/`
- AWS credentials in `/home/bitnami/.aws/credentials`
- Both scripts (`setup_server.sh`, `deploy_update.sh`) in `/home/bitnami/`, executable (`chmod +x`)
- The following environment variables exported (add to `~/.bashrc` or `/etc/environment`):

```bash
export ECR_REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com
export ECR_REPO=<your-org>/<your-app>
export AWS_REGION=<region>
```

---

## Build and push a new image

Set the variables once in your shell (or `~/.bashrc`):

```bash
export ECR_REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com
export ECR_REPO=<your-org>/<your-app>
export AWS_REGION=<region>
export TAG=v1.01
```

Then build and push:

```bash
# 1. Authenticate to ECR (local machine)
aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$ECR_REGISTRY"

# 2. Build
#   --no-cache             : always compile from scratch
#   --platform linux/amd64 : target Lightsail (x86_64), not local arch (e.g. ARM on M1)
#   --provenance=false     : suppress attestation manifests — avoids ECR manifest issues
docker build --no-cache --platform linux/amd64 --provenance=false \
    -t "app:$TAG" .

# 3. Tag for ECR
docker tag "app:$TAG" "$ECR_REGISTRY/$ECR_REPO:$TAG"

# 4. Push
docker push "$ECR_REGISTRY/$ECR_REPO:$TAG"
```

---

## One-time server setup

Run this **once** after the first deploy or after the server is rebuilt. Safe to re-run.

```bash
./setup_server.sh v1.01
```

### What it does

1. **Detects the `spring` user GID** from the image (`id -g spring` via `--entrypoint`)
2. **Resolves the shared group** — finds whichever host group already owns that GID (or creates `springapp` if none does). This group is the bridge between the `spring` container user and `tunneluser` on the host.
3. **Adds `tunneluser`** to the shared group
4. **Sets `authorized_keys` permissions**:
   - `/home/tunneluser/.ssh` → `770` (group-writable)
   - `/home/tunneluser/.ssh/authorized_keys` → `660` (group-writable)
   - Both owned by `tunneluser:<shared-group>`
5. **Configures `sshd`** — appends a `Match User tunneluser` block to `/etc/ssh/sshd_config`:
   ```
   Match User tunneluser
       StrictModes no          ← allows group-writable authorized_keys
       AllowTcpForwarding yes
       GatewayPorts clientspecified
       PermitTTY no
       ForceCommand echo 'Tunnel only'
   ```
6. **Reloads sshd**

> **Why the shared group?** The app container runs as `spring` (Alpine system user, GID determined at image build time). It needs to write `authorized_keys` on the host. The volume mount exposes the file, but the container has no `sudo`. A shared group with matching GID gives the container write access without privilege escalation.

After setup_server.sh completes, restart the container so it picks up the new group membership on the volume mount:

```bash
docker-compose -f docker-compose.prod.yml restart app
```

---

## Routine deployment

```bash
./deploy_update.sh v1.01
```

### What it does

1. Authenticates to ECR (`ecr-puller` profile)
2. Updates the image tag in `docker-compose.prod.yml` via `sed`
3. Pulls the new image
4. Restarts the `app` container only (`--no-deps` — postgres is untouched)
5. Prunes dangling images
6. Tails `iot-app` logs

---

## docker-compose.prod.yml structure

```yaml
services:

  postgres:
    image: postgres:16-alpine
    container_name: iot-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER} -d ${DB_NAME}"]
      interval: 10s
      timeout: 5s
      retries: 5

  app:
    pid: host              # required: lets lsof/kill see host SSH processes
    cap_add:
      - KILL               # required: allows the spring user to kill host processes
    image: <account-id>.dkr.ecr.<region>.amazonaws.com/<ecr-repo>:<TAG>
    container_name: iot-app
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${DB_NAME}
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_PROFILES_ACTIVE: prod
      AWS_REGION: us-east-1
      AWS_SHARED_CREDENTIALS_FILE: /aws/credentials
    volumes:
      - /home/tunneluser/.ssh:/home/tunneluser/.ssh   # authorized_keys bridge
      - /home/bitnami/config:/app/config:ro           # application.properties
      - /home/bitnami/.aws/credentials:/aws/credentials:ro
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  postgres_data:
```

### Key differences from the dev compose

| Setting | Dev | Prod |
|---------|-----|------|
| `pid: host` | absent | required for SSH process visibility |
| `cap_add: KILL` | absent | required to kill SSH tunnels |
| `/home/tunneluser/.ssh` volume | absent | required for authorized_keys management |
| AWS credentials | env vars | file mount + `AWS_SHARED_CREDENTIALS_FILE` |
| DB env vars | hardcoded | `${DB_NAME}` etc. — must be exported in shell |

---

## DB environment variables

The postgres container reads `${DB_NAME}`, `${DB_USER}`, `${DB_PASSWORD}` from the shell environment at `docker-compose up` time. Export them before running either script, or add them to `/etc/environment` on the server:

```bash
export DB_NAME=iot_backend
export DB_USER=app
export DB_PASSWORD=your-password
```
