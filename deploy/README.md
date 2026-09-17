# Deploying the public demo

One small VPS (for example Hetzner CX22: 2 vCPU, 4 GB RAM) runs everything with Docker Compose. See
[ADR 0010](../docs/adr/0010-public-demo-and-observability.md) for why it is built this way.

## Try it locally first

```bash
./gradlew :bootstrap:bootJar
docker build -f deploy/Dockerfile -t abgleich:local .

cd deploy
cp .env.example .env     # ABGLEICH_DOMAIN=:80, ABGLEICH_PUBLIC_URL=http://localhost,
                         # ABGLEICH_IMAGE=abgleich, ABGLEICH_IMAGE_TAG=local, and two passwords
docker compose -f compose.demo.yaml up -d --wait
```

Open <http://localhost> and <http://localhost/grafana>. Then:

```bash
./gradlew :smoke-tests:installBrowser
./gradlew :smoke-tests:smokeTest -Psmoke.baseUrl=http://localhost
docker compose -f compose.demo.yaml down -v   # removes the demo containers and their data
```

## Prepare the server (once)

1. Create the VPS with Ubuntu 24.04 and your SSH key. Point a DNS `A` record (and `AAAA` for IPv6) at it.
2. Log in as root and harden SSH: in `/etc/ssh/sshd_config` set `PasswordAuthentication no` and
   `PermitRootLogin prohibit-password`, then `systemctl reload ssh`.
3. Firewall: `ufw allow OpenSSH && ufw allow 80,443/tcp && ufw allow 443/udp && ufw enable`.
4. Install Docker Engine with the Compose plugin from docker.com, and unattended upgrades
   (`apt install unattended-upgrades`).
5. Create a deploy user that may run Docker and owns the application folder:

   ```bash
   adduser --disabled-password --gecos "" deploy
   usermod -aG docker deploy
   install -d -o deploy -g deploy /opt/abgleich
   ```

6. Create a key pair only for deployments on your machine, add its public key to
   `/home/deploy/.ssh/authorized_keys`.
7. As `deploy`, create `/opt/abgleich/.env` from [.env.example](.env.example) with the real domain and new
   passwords (`openssl rand -base64 32`). It never leaves the server.

## Connect GitHub (once)

Repository → Settings → Secrets and variables → Actions:

| Kind | Name | Value |
|------|------|-------|
| Variable | `ABGLEICH_DEMO_HOST` | server name or IP |
| Variable | `ABGLEICH_DEMO_USER` | `deploy` |
| Variable | `ABGLEICH_DEMO_URL` | `https://your-domain` |
| Secret | `ABGLEICH_DEMO_SSH_KEY` | the private deploy key |
| Secret | `ABGLEICH_DEMO_KNOWN_HOSTS` | output of `ssh-keyscan your-server` |

Create an environment named `demo` (Settings → Environments) if you want to require approval before rollouts.

After the first image push, make the package public (Profile → Packages → abgleich → Package settings → Change
visibility), so the server can pull without registry credentials.

From then on every push to `main` whose CI passes is built, rolled out and smoke tested by
[deploy.yml](../.github/workflows/deploy.yml). It can also be started by hand from the Actions tab.

## Operating

- Logs: `docker compose -f compose.demo.yaml logs -f app`
- The demo resets itself every night at 02:00 UTC and whenever its data exceeds 500 MB.
- Roll back: `ABGLEICH_IMAGE_TAG=<previous tag> docker compose -f compose.demo.yaml up -d --wait`
