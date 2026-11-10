#!/bin/bash
# Buffer GraphQL API helper.
# usage: ./buffer.sh '<graphql query>' ['<variables json>']
# The key is NOT stored in the repo. In cloud sessions it is injected by the environment proxy
# for api.buffer.com (any dummy BUFFER_TOKEN works then); otherwise export BUFFER_TOKEN=<key>.
set -euo pipefail
q=$(python3 -c 'import json,sys; print(json.dumps({"query": sys.argv[1], "variables": json.loads(sys.argv[2])}))' "$1" "${2:-{\}}")
curl -sS --max-time 60 https://api.buffer.com \
  -H "Authorization: Bearer ${BUFFER_TOKEN:-injected-by-proxy}" \
  -H "Content-Type: application/json" -d "$q"
