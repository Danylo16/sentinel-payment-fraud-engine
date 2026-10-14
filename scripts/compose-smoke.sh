#!/usr/bin/env bash
# CI-only ephemeral Compose verification. It never uses the normal project volume.
set -euo pipefail
project="sentinel-ci-${GITHUB_RUN_ID:-$(date +%s)}"
env_file=$(mktemp)
body_file=$(mktemp)
cp .env.example "$env_file"
export SPRING_PROFILES_ACTIVE=demo
export DEMO_FUNDING_TOKEN
DEMO_FUNDING_TOKEN=$(openssl rand -hex 24)
if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then echo "::add-mask::$DEMO_FUNDING_TOKEN"; fi
compose() { docker compose --project-name "$project" --env-file "$env_file" "$@"; }
cleanup() {
    code=$?
    if [[ "$code" != 0 ]]; then compose logs --tail=80 app db || true; fi
    compose down -v --remove-orphans || true
    rm -f "$env_file" "$body_file"
}
trap cleanup EXIT
compose up --build -d
ready=false
for _ in $(seq 1 60); do
    if curl --fail --silent http://127.0.0.1:8080/v3/api-docs >/dev/null; then ready=true; break; fi
    sleep 1
done
[[ "$ready" == "true" ]]
a=$(curl --fail --silent --show-error -H 'Content-Type: application/json' -d '{"name":"Compose Alice"}' http://127.0.0.1:8080/accounts | jq -r .id)
b=$(curl --fail --silent --show-error -H 'Content-Type: application/json' -d '{"name":"Compose Bob"}' http://127.0.0.1:8080/accounts | jq -r .id)
[[ "$a" =~ ^[0-9a-f-]{36}$ && "$b" =~ ^[0-9a-f-]{36}$ ]]
curl --fail --silent --show-error -H 'Content-Type: application/json' -H "X-Demo-Token: $DEMO_FUNDING_TOKEN" \
    -H "Idempotency-Key: $(openssl rand -hex 16)" -d "{\"targetAccountId\":\"$a\",\"amountCents\":1000}" \
    http://127.0.0.1:8080/demo/fundings >/dev/null
key=$(openssl rand -hex 16)
request="{\"sourceAccountId\":\"$a\",\"targetAccountId\":\"$b\",\"amountCents\":100}"
status=$(curl --silent --show-error -o "$body_file" -w '%{http_code}' -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" -d "$request" http://127.0.0.1:8080/transfers)
[[ "$status" == 201 ]]
id=$(jq -r .id "$body_file")
[[ "$id" =~ ^[0-9a-f-]{36}$ ]]
status=$(curl --silent --show-error -o "$body_file" -w '%{http_code}' -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" -d "$request" http://127.0.0.1:8080/transfers)
[[ "$status" == 200 && "$(jq -r .id "$body_file")" == "$id" ]]
curl --fail --silent "http://127.0.0.1:8080/accounts/$a" | jq -e '.balanceCents == 900' >/dev/null
curl --fail --silent "http://127.0.0.1:8080/accounts/$b" | jq -e '.balanceCents == 100' >/dev/null
[[ "$(compose exec -T db psql -U sentinel -d sentinel -Atc 'SELECT sum(balance_cents) FROM accounts')" == 0 ]]
[[ "$(compose exec -T db psql -U sentinel -d sentinel -Atc "SELECT count(*)=2 AND sum(signed_cents)=0 FROM ledger_entries WHERE transfer_id='$id'")" == t ]]
echo 'PASS: Compose startup, migration, protected funding, transfer, replay, balances and ledger conservation'
