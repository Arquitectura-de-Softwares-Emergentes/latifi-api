# Deploy latifi-api on Dokploy

This runbook takes latifi-api from a repository to a public HTTPS API on your own Dokploy instance (the Hetzner VPS of ADR-0006). Scripts do everything the Dokploy API can do. You do the three steps it cannot: the tenant database on the VPS, the DNS record and the API token.

Every script lives in `deploy/dokploy/` and runs on your Mac. They need `bash`, `curl`, `jq` and, for the live check, `openssl`. Run them from the root of the `latifi-api` repository.

## What runs where

1. A client reaches `https://API_HOST`. Traefik on the VPS terminates TLS with a Let's Encrypt certificate and redirects plain http to https.
2. Traefik forwards the request to the `latifi-api` container on the `dokploy-network` Docker network, port 8080. The container publishes no port of its own, so Traefik is the only way in.
3. The container reads and writes its tenant database in `shared-postgres`, reachable on the same network as `shared-postgres:5432`. It uses one database user that owns only that database.
4. The indexer runs inside the same process. It polls the dedicated Polygon Amoy node over https with the key you keep in `LATIFI_RPC_URL`.

Dokploy builds the image from the `Dockerfile` of the repository. The build has no route to `shared-postgres`, and the Dockerfile needs none.

## Files that stay on your Mac

All private files live in `~/.latifi/`, outside every repository. The scripts read them line by line and never execute them. They refuse a file that other users can read, so keep mode 600.

| File | Holds | Made in |
| ---- | ----- | ------- |
| `~/.latifi/dokploy.env` | `DOKPLOY_URL` and `DOKPLOY_TOKEN` | step 4 |
| `~/.latifi/latifi-api.env` | the twelve runtime variables, built from `deploy/dokploy/app.env.example` | step 5 |
| `~/.latifi/rpc.env` | `AMOY_RPC_URL`, already made in Phase 1 | Phase 1 |
| `~/.latifi/dokploy-state.json` | ids of the project and the application, no secret | written by `provision.sh` |

Never type a secret as a plain command argument and never paste one into a chat. The steps below use `read -rs`, which reads a value without echo and keeps it out of your shell history. The blocks work in bash and in zsh.

## One time prerequisites

Steps 1 to 5 need you. Do them once.

1. Pick the public host name, for example `api.latifi.example`, and create the DNS record. Add an `A` record from that name to the public IP of the VPS. If the zone is on Cloudflare, set the record to DNS only (grey cloud) until the certificate exists. A proxied record can break the Let's Encrypt HTTP-01 challenge. Check it with `dig +short api.latifi.example`, which must print the VPS IP.

2. Create the tenant database. Connect to the VPS over SSH and run, from the `hosting` repository:

   ```
   infra/db/new-tenant-postgres.sh latifi
   ```

   The script prints the host, the database name, the user and a password. Keep that output. The internal host is `shared-postgres` and the port is `5432`. The JDBC URL for the next steps is `jdbc:postgresql://shared-postgres:5432/DATABASE`. The password must be letters and digits only, or the connection string breaks. A hex password is fine.

3. Make the signing secret and the node key ready. The signing secret comes from `openssl rand -hex 32` (step 5 makes it for you). The node URL is `AMOY_RPC_URL` in `~/.latifi/rpc.env`.

4. Create the Dokploy API token and the private token file.
   1. Open the Dokploy panel, go to Settings, Profile, API Token and create a token.
   2. The panel answers on port 3000. Do not send the token over plain http across the internet. Open an SSH tunnel in a second terminal and leave it running: `ssh -N -L 3000:localhost:3000 USER@VPS_IP`. The scripts accept plain http only for localhost. An https panel domain also works.
   3. Write the file:

      ```
      mkdir -p ~/.latifi && chmod 700 ~/.latifi
      printf 'Dokploy API token: '; read -rs DK_TOKEN; echo
      (umask 077; printf 'DOKPLOY_URL=%s\nDOKPLOY_TOKEN=%s\n' "http://localhost:3000" "$DK_TOKEN" > ~/.latifi/dokploy.env)
      unset DK_TOKEN
      ```

5. Build the runtime file. This block reads the database password without echo, generates the signing secret, copies the node URL from `rpc.env` and asks the node for the start block. It never prints a secret. Replace `API_HOST` and `DATABASE`.

   ```
   API_HOST=api.latifi.example
   DATABASE=latifi
   DB_USER=latifi
   printf 'Database password: '; read -rs DB_PASSWORD; echo
   JWT_SECRET="$(openssl rand -hex 32)"
   RPC_URL="$(grep -E '^AMOY_RPC_URL=' ~/.latifi/rpc.env | cut -d= -f2-)"
   START_BLOCK="$(bash deploy/dokploy/start-block.sh | cut -d= -f2)"
   LOANBOOK="$(jq -r '.contracts.LoanBook.address' ../latifi-contracts/deployments/amoy.json)"
   (umask 077; cat > ~/.latifi/latifi-api.env <<EOF
   SPRING_DATASOURCE_URL=jdbc:postgresql://shared-postgres:5432/$DATABASE
   SPRING_DATASOURCE_USERNAME=$DB_USER
   SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD
   LATIFI_JWT_SECRET=$JWT_SECRET
   LATIFI_AUTH_DOMAIN=$API_HOST
   LATIFI_AUTH_URI=https://$API_HOST
   LATIFI_TRUST_FORWARDED_FOR=true
   SERVER_FORWARD_HEADERS_STRATEGY=framework
   LATIFI_RPC_URL=$RPC_URL
   LATIFI_LOANBOOK_ADDRESS=$LOANBOOK
   LATIFI_INDEXER_START_BLOCK=$START_BLOCK
   LATIFI_SWAGGER_ENABLED=true
   EOF
   )
   unset DB_PASSWORD JWT_SECRET RPC_URL
   ```

   `start-block.sh` asks the node for the current head, refuses any chain other than Polygon Amoy (chain id 80002) and answers the head minus 50 blocks, never below the deployment block 51675536. If loans already exist on chain, the indexer must start at or before the block of the first event. In that case run `bash deploy/dokploy/start-block.sh --first-event-block N` with that block and use its answer. The start block counts only on a fresh database. After the indexer saves a checkpoint, it continues from the checkpoint.

   `app.env.example` lists the twelve names and where each value comes from. The kit manages exactly those twelve variables and ignores any other name in the file.

## First deploy

1. Run the dry run. It checks the file and prints the plan. It makes no network call and does not read the token file. Secrets show as `***` and the node URL as its host.

   ```
   bash deploy/dokploy/provision.sh --host api.latifi.example
   ```

   Read the plan. A wrong value stops the run with exit code 2 and names the key, never the value.

2. Apply it and queue the first deploy. Keep the SSH tunnel open.

   ```
   bash deploy/dokploy/provision.sh --host api.latifi.example --apply --deploy
   ```

   The script creates the project `latifi` and the application `latifi-api` when they do not exist, sets the Git source and the Dockerfile build, writes the environment, adds the domain with a Let's Encrypt certificate and queues the deploy. You can run it again at any time. It creates nothing twice and applies a changed branch or a changed environment.

3. Watch the build and the container. `applicationStatus` `done` only means the build finished. Always read the container line as well.

   ```
   bash deploy/dokploy/ops.sh status
   bash deploy/dokploy/ops.sh logs --tail 100
   ```

4. When `status` shows a running container, check the public surface:

   ```
   bash deploy/dokploy/smoke-live.sh https://api.latifi.example --evidence ~/.latifi/smoke-live.json
   bash deploy/dokploy/indexer-status.sh
   ```

   `smoke-live.sh` checks the http to https redirect, the certificate chain and its 14 days of validity, `/actuator/health` (UP, JSON, no details), Swagger UI, the OpenAPI server URL, a 401 on a protected route, hidden `/actuator/env` and `/actuator/beans`, and a wallet challenge that names the public host. It ends with `smoke-live: ok` or names the first failing check. `indexer-status.sh` answers `indexer: running`, `indexer: off` or `indexer: failing`.

5. If the zone is on Cloudflare, switch the record back to proxied now if you want it, then run the smoke check again.

## Redeploy and auto deploy

The provisioning uses a custom Git URL. A custom Git URL has no push webhook, so a new commit does not deploy itself. After you merge a release, run:

```
bash deploy/dokploy/ops.sh redeploy
```

If the Dokploy GitHub App covers the repository, provision once with its id and every push to the branch deploys by itself. Find the id of the GitHub provider in the Git provider settings of the panel:

```
bash deploy/dokploy/provision.sh --host api.latifi.example --github-id GITHUB_APP_ID --apply
```

The repository is public, so the plain Git URL works without credentials. If it becomes private, use the GitHub App path.

## Day two commands

| Command | What it does |
| ------- | ------------ |
| `ops.sh status` | Build status and the real container state and health. |
| `ops.sh logs --tail 300` | Runtime log with every known secret hidden. |
| `ops.sh stop` and `ops.sh start` | Graceful stop and start. |
| `ops.sh redeploy` | Rebuild from the Git source and replace the container. |
| `ops.sh kill` | Kill the container like a crash. The orchestrator restarts it. The indexer rehearsal uses it. |
| `indexer-status.sh` | Reads the log and says whether the indexer runs. |

Each command prints the UTC time it ran. If the API refuses `kill`, the script exits with code 5 and prints the `docker kill` line to run over SSH on the VPS.

## Failure playbook

1. The build finished but the container keeps restarting. Do not read the build log. Run `ops.sh status`, which shows repeated restarts, then `ops.sh logs`. The runtime log holds the cause.
2. The log says `LATIFI_JWT_SECRET is too short`. The secret needs at least 32 characters. Fix it in `latifi-api.env`, run `provision.sh --apply` and then `ops.sh redeploy`. The provisioning script also refuses a short secret before it sends anything.
3. The log shows a database connection error. Check that the host is `shared-postgres`, the port is 5432, and the user, password and database match the output of `new-tenant-postgres.sh`. A runtime container reaches `shared-postgres` over `dokploy-network`. A build never does, so a build error that mentions the database means someone added a build step that connects to it. Remove that step.
4. The certificate is not issued. Check that the DNS record resolves to the VPS IP and that the Cloudflare proxy is off. Traefik retries by itself. If nothing changes after ten minutes, open the domain in the panel and save it again. Then run `smoke-live.sh` once more.
5. `indexer: off`. The startup line says `latifi.chain.rpc-url is empty`. Set `LATIFI_RPC_URL` in `latifi-api.env`, run `provision.sh --apply`, then `ops.sh redeploy`.
6. `indexer: failing`. Read the last indexer lines. A rate limited node shows as failed cycles. The indexer retries each cycle, so wait and run the check again. If it persists, look at the usage page of your node provider.
7. The first sync is very slow. The start block is too early. A scan from the deployment block 51675536 is about 680000 blocks. Make a new value with `start-block.sh`, set it in `latifi-api.env`, apply and redeploy. This only helps while the database has no checkpoint.
8. `smoke-live.sh` fails on `openapi`. The OpenAPI server URL is not the https origin. Check that `SERVER_FORWARD_HEADERS_STRATEGY=framework` reached the container, then redeploy.

## Rollback

Point the source at the previous release and deploy it. A tag name works where Git accepts it as a branch. If Dokploy refuses the tag, push a branch at the old commit and use that branch name.

```
bash deploy/dokploy/provision.sh --host api.latifi.example --branch v0.1.0 --apply --deploy
```

When the problem is fixed, run the same command with `--branch main` to go back.

## Backups

Provisioning does not back up the new database. Add the `latifi` tenant database to the daily backup crontab described in `infra/db/RUNBOOK.md` of the hosting repository, and keep a copy of the Dokploy configuration as the hosting runbook says. ADR-0006 puts both duties on you.

## Security notes

1. Secrets live only in the Dokploy environment of the application and in your private files under `~/.latifi/`. No repository, test or log holds one.
2. The scripts send the token and every request body to curl through standard input. They never appear in a process list.
3. Swagger UI and the OpenAPI document are public on purpose. Set `LATIFI_SWAGGER_ENABLED=false` to hide both. Today `provision.sh` insists on `true`, so edit its check before you hide them, and expect `smoke-live.sh` to fail on Swagger UI and on the OpenAPI document afterwards.
4. The API trusts the `X-Forwarded-For` header (`LATIFI_TRUST_FORWARDED_FOR=true`). That is safe only because the container port is not published and Traefik is the only entry. Never publish port 8080 on the VPS.
5. The node URL carries a key. The application logs the host only, and the scripts hide the key pattern in every log they print.
