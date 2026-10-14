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
curl --fail --silent "http://127.0.0.1:8080/transfers/$id/fraud" | jq -e '.decision == "APPROVE" and .score == 20' >/dev/null
# Fresh source/recipient produces REVIEW from large amount + new recipient.
r=$(curl --fail --silent -H 'Content-Type: application/json' -d '{"name":"Review source"}' http://127.0.0.1:8080/accounts | jq -r .id)
c=$(curl --fail --silent -H 'Content-Type: application/json' -d '{"name":"Fresh recipient"}' http://127.0.0.1:8080/accounts | jq -r .id)
curl --fail --silent -H 'Content-Type: application/json' -H "X-Demo-Token: $DEMO_FUNDING_TOKEN" \
    -H "Idempotency-Key: $(openssl rand -hex 16)" -d "{\"targetAccountId\":\"$r\",\"amountCents\":200000}" \
    http://127.0.0.1:8080/demo/fundings >/dev/null
review_key=$(openssl rand -hex 16)
review_request="{\"sourceAccountId\":\"$r\",\"targetAccountId\":\"$c\",\"amountCents\":100000}"
status=$(curl --silent --show-error -o "$body_file" -w '%{http_code}' -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $review_key" -d "$review_request" http://127.0.0.1:8080/transfers)
[[ "$status" == 202 && "$(jq -r .status "$body_file")" == REVIEW ]]
review_id=$(jq -r .id "$body_file")
curl --fail --silent "http://127.0.0.1:8080/transfers/$review_id/fraud" | jq -e '.decision == "REVIEW" and .score == 55' >/dev/null
status=$(curl --silent --show-error -o "$body_file" -w '%{http_code}' -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $review_key" -d "$review_request" http://127.0.0.1:8080/transfers)
[[ "$status" == 200 && "$(jq -r .id "$body_file")" == "$review_id" ]]
curl --fail --silent "http://127.0.0.1:8080/accounts/$r" | jq -e '.balanceCents == 200000' >/dev/null
# Five completed samples then a large transfer to a fresh recipient produces DECLINE.
for _ in $(seq 1 4); do
    curl --fail --silent -H 'Content-Type: application/json' -H "Idempotency-Key: $(openssl rand -hex 16)" \
        -d "$request" http://127.0.0.1:8080/transfers | jq -e '.status == "COMPLETED"' >/dev/null
done
status=$(curl --silent --show-error -o "$body_file" -w '%{http_code}' -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(openssl rand -hex 16)" \
    -d "{\"sourceAccountId\":\"$a\",\"targetAccountId\":\"$c\",\"amountCents\":100000}" http://127.0.0.1:8080/transfers)
[[ "$status" == 201 && "$(jq -r .status "$body_file")" == DECLINED ]]
decline_id=$(jq -r .id "$body_file")
curl --fail --silent "http://127.0.0.1:8080/transfers/$decline_id/fraud" | jq -e '.decision == "DECLINE" and .score == 100' >/dev/null
curl --fail --silent "http://127.0.0.1:8080/accounts/$a" | jq -e '.balanceCents == 500' >/dev/null
curl --fail --silent "http://127.0.0.1:8080/accounts/$b" | jq -e '.balanceCents == 500' >/dev/null
curl --fail --silent "http://127.0.0.1:8080/accounts/$c" | jq -e '.balanceCents == 0' >/dev/null
[[ "$(compose exec -T db psql -U sentinel -d sentinel -Atc "SELECT count(*) FROM ledger_entries WHERE transfer_id IN ('$review_id','$decline_id')")" == 0 ]]
[[ "$(compose exec -T db psql -U sentinel -d sentinel -Atc 'SELECT sum(balance_cents) FROM accounts')" == 0 ]]
echo 'PASS: Compose migrations, funding, APPROVE/REVIEW/DECLINE, replay, balances and ledger conservation'
