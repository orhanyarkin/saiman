#!/usr/bin/env bash
# Offline self-test of readiness.sh (ADR-0028): fake servers on loopback that answer with the REAL /actuator/health bodies of
# Spring Boot 4.1.1 (testdata/health, see PROVENANCE.md), then the same script in the pinned busybox image when it is local.
# Needs bash, python3, wget and nc. No AWS.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fx="${here}/testdata/health"
work="$(mktemp -d)"
pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "${pid}" 2>/dev/null || true; done
  rm -rf "${work}"
}
trap cleanup EXIT

fails=0
ok() { echo "ok:   $*"; }
bad() {
  echo "FAIL: $*" >&2
  fails=$((fails + 1))
}
show() { sed 's/^/      /' "$1" >&2; }

free_port() { python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
serve() { # <http status> <body file> <port>: every GET answers with that status and body
  python3 -I -c '
import http.server, sys
status, body = int(sys.argv[1]), open(sys.argv[2], "rb").read()
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a):
        pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[3])), H).serve_forever()
' "$1" "$2" "$3" >/dev/null 2>&1 &
  pids+=("$!")
}
serve_fixture() { # <fixture name> <port>
  serve "$(cat "${fx}/$1.status")" "${fx}/$1.json" "$2"
}
# run <output file> <targets> <timeout> [extra env...]: readiness.sh against targets, output (stdout+stderr) in the file
run() {
  local out="$1" targets="$2" timeout="$3"
  shift 3
  env "$@" READINESS_TARGETS="${targets}" READINESS_TIMEOUT_SECONDS="${timeout}" READINESS_INTERVAL=1 "${here}/readiness.sh" >"${out}" 2>&1
}
no_body_in() { # <file>: names and statuses only, no JSON and none of the details the fixtures carry
  ! grep -qE 'visible-detail|secret-looking|PostgreSQL|validationQuery|isValid|/workspace|livenessState|diskSpace|db=|ping=|ssl=|[{}"]' "$1"
}

# --- the real bodies ------------------------------------------------------------------------------------------
pu1="$(free_port)" pu2="$(free_port)" pu3="$(free_port)"
pd1="$(free_port)" pd2="$(free_port)" pd3="$(free_port)" pclosed="$(free_port)"
serve_fixture up-components-never "${pu1}"
serve_fixture up-components-always "${pu2}"
printf '{}' >"${work}/empty.json"
serve 200 "${work}/empty.json" "${pu3}"
serve_fixture down-components-always "${pd1}"
serve_fixture down-details-always "${pd2}"
serve_fixture down-components-never "${pd3}"
sleep 1

# Boot writes the keys alphabetically: status is the LAST key. The first readiness.sh only knew a body that starts with {"status":"UP".
if run "${work}/up.txt" "never:${pu1} always:${pu2}" 10; then ok "a real UP body (status last), with and without components, is UP (HTTP 200)"; else
  bad "readiness.sh does not accept the real UP bodies"
  show "${work}/up.txt"
fi
if grep -q 'never (port' "${work}/up.txt" && grep -q 'always (port' "${work}/up.txt" && ! grep -qE 'status|components|groups|[{}]' "${work}/up.txt"; then ok "the UP output names services only"; else bad "unexpected UP output"; fi
if run "${work}/up-empty.txt" "bare:${pu3}" 10; then ok "UP is decided by HTTP 200 alone, whatever the body"; else bad "an HTTP 200 with an unrelated body was not counted as UP"; fi

# 503 is not UP, and the failure names the DOWN component and nothing else.
start="$(date +%s)"
if run "${work}/down.txt" "alpha:${pu1} gamma:${pd1}" 3; then bad "readiness.sh exited 0 although a service answers 503"; else ok "a service that answers 503 keeps the gate closed (non-zero at the timeout)"; fi
elapsed=$(($(date +%s) - start))
if [[ ${elapsed} -ge 3 && ${elapsed} -lt 15 ]]; then ok "timeout honoured (${elapsed}s for a 3s limit)"; else bad "timeout took ${elapsed}s"; fi
if grep -qF 'gamma (port '"${pd1}"'): HTTP 503, status DOWN, components not UP: probe=DOWN' "${work}/down.txt"; then ok "the failure says which component is DOWN (probe=DOWN)"; else
  bad "the failure does not name the DOWN component"
  show "${work}/down.txt"
fi
if no_body_in "${work}/down.txt"; then ok "the failure output holds names and statuses only (no UP component, no JSON)"; else
  bad "the failure output leaks a component that is UP, details or raw JSON"
  show "${work}/down.txt"
fi
if ! grep -q 'alpha (port .*not UP' "${work}/down.txt"; then ok "a service that is UP is not reported as failing"; else bad "alpha is UP but was reported"; fi

# Details are never switched on in the demo; if they were, none of them may reach the log, and the component is still found
# although its keys are details first, status last.
run "${work}/details.txt" "detail:${pd2}" 2 || true
if grep -qF 'detail (port '"${pd2}"'): HTTP 503, status DOWN, components not UP: probe=DOWN' "${work}/details.txt" && no_body_in "${work}/details.txt"; then ok "with health details on: the DOWN component is found and no detail is printed"; else
  bad "details leaked, or the DOWN component was missed when status is the last key of the component"
  show "${work}/details.txt"
fi
run "${work}/never.txt" "bare:${pd3}" 2 || true
if grep -qF 'bare (port '"${pd3}"'): HTTP 503, status DOWN, no component listed as not UP (is MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS=always set?)' "${work}/never.txt"; then ok "components not shown: a hint instead of a guess"; else
  bad "no hint for a body without components"
  show "${work}/never.txt"
fi
run "${work}/closed.txt" "closed:${pclosed}" 2 || true
if grep -q 'closed (port '"${pclosed}"'): no answer' "${work}/closed.txt"; then ok "a service that does not listen is reported as no answer"; else bad "no 'no answer' line for a closed port"; fi

# The key order must not matter for the explanation (a different Jackson or Boot version, or a hand-made body).
printf '{"status":"DOWN","components":{"redis":{"status":"DOWN"},"db":{"status":"UP"}},"groups":["liveness"]}' >"${work}/first.json"
printf '{"components":{"redis":{"details":{"error":"NOAUTH leaked"},"status":"DOWN"},"kafka":{"status":"OUT_OF_SERVICE"}},"status":"DOWN"}' >"${work}/mixed.json"
pf1="$(free_port)" pf2="$(free_port)"
serve 503 "${work}/first.json" "${pf1}"
serve 503 "${work}/mixed.json" "${pf2}"
sleep 1
run "${work}/first.txt" "first:${pf1}" 2 || true
run "${work}/mixed.txt" "mixed:${pf2}" 2 || true
if grep -qF 'status DOWN, components not UP: redis=DOWN' "${work}/first.txt"; then ok "status as the first key: same explanation"; else
  bad "status-first body not understood"
  show "${work}/first.txt"
fi
if grep -qF 'status DOWN, components not UP: redis=DOWN kafka=OUT_OF_SERVICE' "${work}/mixed.txt" && ! grep -q 'NOAUTH\|leaked' "${work}/mixed.txt"; then ok "several components, details first: found, error text not printed"; else
  bad "mixed key order not understood or the error text leaked"
  show "${work}/mixed.txt"
fi

# A component name is printed only if it is a plain token: a hostile or odd key (spaces, quotes, markup, long text) never is.
printf '{"components":{"bad key with spaces and \\"quotes\\" <b>":{"status":"DOWN"},"line\\nbreak":{"status":"DOWN"},"redis":{"status":"DOWN"}},"status":"DOWN"}' >"${work}/odd.json"
podd="$(free_port)"
serve 503 "${work}/odd.json" "${podd}"
sleep 1
run "${work}/odd.txt" "odd:${podd}" 2 || true
if grep -qF 'components not UP: redis=DOWN' "${work}/odd.txt" && ! grep -qE 'bad key|quotes|<b>|break|spaces' "${work}/odd.txt"; then ok "only plain component names are printed (odd keys are dropped)"; else
  bad "a component name that is not a plain token was printed"
  show "${work}/odd.txt"
fi

# Progress reports while waiting, not only at the end.
run "${work}/progress.txt" "gamma:${pd1}" 5 READINESS_REPORT_INTERVAL=2 || true
if [[ "$(grep -c 'components not UP: probe=DOWN' "${work}/progress.txt")" -ge 2 ]]; then ok "the cause is reported while the gate is still waiting, and again at the timeout"; else
  bad "no progress report before the timeout"
  show "${work}/progress.txt"
fi

# A service that comes up late: 503 first, 200 after ~2 s.
plate="$(free_port)"
python3 -I -c '
import http.server, sys, time
t0 = time.time()
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        up = time.time() - t0 > 2
        body = b"{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}" if up else b"{\"groups\":[],\"status\":\"DOWN\"}"
        self.send_response(200 if up else 503)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a):
        pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
' "${plate}" >/dev/null 2>&1 &
pids+=("$!")
sleep 1
if run "${work}/late.txt" "late:${plate}" 10; then ok "the gate opens when a service goes from 503 to 200"; else
  bad "the gate did not pick up the late service"
  show "${work}/late.txt"
fi

# --- the same script in the real image of the readiness container ---------------------------------------------
# busybox pinned by digest in demo-lite/containers.tf: the host's wget, nc, sed and awk are not busybox's. Runs only when that
# exact image is already local (no pull: the registry rate-limits anonymous pulls).
bb_digest="$(sed -nE 's|.*busybox:[0-9.]+@(sha256:[0-9a-f]{64}).*|\1|p' "${here}/../demo-lite/containers.tf" | head -n 1)"
if [[ -n "${bb_digest}" ]] && command -v docker >/dev/null 2>&1 && docker image inspect "busybox@${bb_digest}" >/dev/null 2>&1; then
  docker run --rm -v "${fx}:/fx:ro" -v "${here}/readiness.sh:/readiness.sh:ro" "busybox@${bb_digest}" sh -c '
    cat > /handler.sh <<EOS
#!/bin/sh
code=\$(cat /fx/\$1.status); body=\$(cat /fx/\$1.json)
printf "HTTP/1.1 %s X\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s" "\$code" "\${#body}" "\$body"
EOS
    chmod +x /handler.sh
    nc -ll -p 18501 -e /handler.sh up-components-always &
    nc -ll -p 18502 -e /handler.sh up-components-never &
    nc -ll -p 18503 -e /handler.sh down-components-always &
    nc -ll -p 18504 -e /handler.sh down-details-always &
    nc -ll -p 18505 -e /handler.sh down-components-never &
    sleep 1
    READINESS_TARGETS="a:18501 b:18502 c:18503 d:18504 e:18505 f:18506" READINESS_TIMEOUT_SECONDS=3 READINESS_INTERVAL=1 sh /readiness.sh' >"${work}/busybox.txt" 2>&1 || true
  if grep -q 'a (port 18501) is UP' "${work}/busybox.txt" && grep -q 'b (port 18502) is UP' "${work}/busybox.txt" \
    && grep -qF 'c (port 18503): HTTP 503, status DOWN, components not UP: probe=DOWN' "${work}/busybox.txt" \
    && grep -qF 'd (port 18504): HTTP 503, status DOWN, components not UP: probe=DOWN' "${work}/busybox.txt" \
    && grep -qF 'e (port 18505): HTTP 503, status DOWN, no component listed as not UP' "${work}/busybox.txt" \
    && grep -qF 'f (port 18506): no answer' "${work}/busybox.txt" && no_body_in "${work}/busybox.txt"; then
    ok "in the pinned busybox image: real UP bodies are UP, real 503 bodies are explained, nothing else is printed"
  else
    bad "readiness.sh misbehaves in the pinned busybox image"
    show "${work}/busybox.txt"
  fi
else
  echo "skip: the pinned busybox image is not local, readiness.sh was only tested with the host's tools"
fi

if [[ ${fails} -ne 0 ]]; then
  echo "test-readiness: ${fails} check(s) FAILED" >&2
  exit 1
fi
echo "test-readiness: all checks passed"
