# Deployment Guide

The app runs in Docker on an AWS Lightsail instance, pulled from ECR. As of the
`feature/ci-cd-pipeline` merge, deploys to `master` happen automatically via
GitHub Actions. Manual deploy is still available as a fallback.

| Path | When |
|------|------|
| **Automated** (`.github/workflows/deploy.yml`) | Every push to `master` (i.e. every merged PR) |
| **Manual** (`deploy_update.sh` from your machine) | Fallback if Actions is down, or a one-off hotfix |
| `setup_server.sh` | Once — after first deploy or after the server is rebuilt |

---

## Automated deploy (primary path)

On push to `master`:
1. `build-and-push` job builds the Docker image and pushes it to ECR, tagged
   `sha-<7-char-short-sha>` (auth via GitHub OIDC — no static AWS keys stored
   anywhere).
2. `deploy` job copies the current `deploy_update.sh` to the server and runs
   it over SSH with `CI=true`, which pulls the new tag, restarts the `app`
   container, and polls briefly for a successful Spring Boot startup instead
   of tailing logs forever.

Branch protection on `master` requires the `build-and-test` CI check to pass
before a PR can be merged.

### GitHub configuration (repo Settings → Secrets and variables → Actions)

Needed if you ever have to recreate this from scratch (e.g. rotating the
deploy key, or setting up a second environment):

**Variables**: `AWS_ROLE_ARN`, `AWS_REGION`, `ECR_REGISTRY`, `ECR_REPO`,
`DEPLOY_HOST`, `DEPLOY_USER`

**Secrets**: `DEPLOY_SSH_KEY` — private half of a dedicated deploy keypair
(`ssh-keygen -t ed25519`), whose public half is appended to
`~/.ssh/authorized_keys` for `DEPLOY_USER` on the server. Never reuse a
personal SSH key for this.

### AWS IAM role for GitHub OIDC

Role `github-actions-ecr-deploy`, trust policy scoped to this repo and
`master` only:
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<account-id>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike": { "token.actions.githubusercontent.com:sub": "repo:<gh-user>/<repo>:ref:refs/heads/master" }
    }
  }]
}
```
Permission policy attached: `ecr:GetAuthorizationToken` (`Resource: *`) plus
`ecr:BatchCheckLayerAvailability`, `GetDownloadUrlForLayer`, `BatchGetImage`,
`PutImage`, `InitiateLayerUpload`, `UploadLayerPart`, `CompleteLayerUpload`
scoped to the specific ECR repository ARN.

### ⚠️ Server `application.properties` must be complete — no local safety net

Before this pipeline, every image was built by hand from a developer machine
that had its own (gitignored) `src/main/resources/application.properties`,
which Gradle silently baked into the JAR. That masked missing server config
for months.

GitHub Actions checks out a **clean** copy of the repo — `application.properties`
is gitignored on purpose (it holds real secrets) and is never present at
build time. The image now depends entirely on `/home/ubuntu/application.properties`
on the server (mounted at runtime) being complete. These properties have no
default and crash the app at startup if missing:

`admin.api-key`, `jwt.secret`, `aws.region`, `aws.iot.endpoint`,
`aws.iot.clientId`, `iot.instanceName`, `cors.allowed-origins`,
`tunnel.server.host`

If you add a new required `@Value("${...}")` (no default) anywhere in the
codebase, add it to the server's `application.properties` in the same PR —
otherwise the next deploy will crash-loop the container in production with
no local build to hide behind.

---

## Manual deploy (fallback)

### Local machine prerequisites
- Docker with `linux/amd64` build support
- AWS CLI profile `ecr-deployer` (or equivalent) with ECR push permission

### Build and push a new image
```bash
export ECR_REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com
export ECR_REPO=plomo-uy/lightsail-app
export AWS_REGION=us-east-1
export TAG=v1.04   # or any tag you like — CI uses sha-<short-sha>

aws ecr get-login-password --region "$AWS_REGION" --profile ecr-deployer \
    | docker login --username AWS --password-stdin "$ECR_REGISTRY"

docker build --no-cache --platform linux/amd64 --provenance=false \
    -t "$ECR_REGISTRY/$ECR_REPO:$TAG" .

docker push "$ECR_REGISTRY/$ECR_REPO:$TAG"
```

### Deploy it
```bash
export ECR_REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com
export ECR_REPO=plomo-uy/lightsail-app
export AWS_REGION=us-east-1

./deploy_update.sh v1.04
```
Run from `/home/ubuntu` on the server (or scp the script there first). Without
`CI=true` set, it tails `iot-app` logs at the end (Ctrl+C to stop) instead of
polling and exiting.

### Rollback
Docker keeps previously pulled images cached locally on the server (only
dangling/untagged layers get pruned). To roll back:
```bash
sed -i 's|lightsail-app:.*|lightsail-app:<previous-tag>|' /home/ubuntu/docker-compose.prod.yml
docker compose -f /home/ubuntu/docker-compose.prod.yml up -d --no-deps app
```
No re-pull needed if that tag's image is still cached (`docker images` to check).

---

## One-time server setup

Run this **once** after the first deploy or after the server is rebuilt.
Safe to re-run.

```bash
./setup_server.sh v1.04
```

### What it does

1. **Detects the `spring` user GID** from the image (`id -g spring` via `--entrypoint`)
2. **Resolves the shared group** — finds whichever host group already owns that GID (or creates `springapp` if none does). This group is the bridge between the `spring` container user and `tunneluser` on the host.
3. **Adds `tunneluser`** to the shared group
4. **Sets `authorized_keys` permissions**:
   - `/home/tunneluser/.ssh` → `770` (group-writable)
   - `/home/tunneluser/.ssh/authorized_keys` → `660` (group-writable)
   - Both owned by `tunneluser:<shared-group>`
5. **Configures `sshd`** — appends a `Match User tunneluser` block to `/etc/ssh/sshd_config` (plus a global `StrictModes no`, required for the group-writable `authorized_keys` above), validates the config with `sshd -t`, then reloads.

> **Why the shared group?** The app container runs as `spring` (Alpine system user, GID determined at image build time). It needs to write `authorized_keys` on the host. The volume mount exposes the file, but the container has no `sudo`. A shared group with matching GID gives the container write access without privilege escalation.

After `setup_server.sh` completes, restart the container so it picks up the new group membership on the volume mount:
```bash
docker compose -f /home/ubuntu/docker-compose.prod.yml restart app
```

---

## `docker-compose.prod.yml` (current, actual)

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
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER} -d ${DB_NAME}"]
      interval: 10s
      timeout: 5s
      retries: 5
    # No port published to the host — only reachable from the app container

  app:
    image: <account-id>.dkr.ecr.<region>.amazonaws.com/plomo-uy/lightsail-app:<TAG>
    container_name: iot-app
    restart: unless-stopped
    env_file:
      - /home/ubuntu/.env
    volumes:
      - ./application.properties:/app/application.properties
      - /home/ubuntu/.aws:/home/spring/.aws:ro
      - /home/tunneluser/.ssh:/home/tunneluser/.ssh
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${DB_NAME}
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  postgres_data:
```

Lives at `/home/ubuntu/docker-compose.prod.yml` on the server. `deploy_update.sh`
only edits the `app` image tag line via `sed` — everything else is set once
during initial server setup.

---

## DB environment variables

The postgres container reads `${DB_NAME}`, `${DB_USER}`, `${DB_PASSWORD}` from
the shell environment at `docker compose up` time — exported in `/home/ubuntu/.env`
(loaded by the `app` service's `env_file`, and sourced manually before running
`docker compose` commands directly on the postgres service).

```bash
export DB_NAME=iot_backend
export DB_USER=app
export DB_PASSWORD=your-password
```
