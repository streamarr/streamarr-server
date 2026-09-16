#!/usr/bin/env bash
set -euo pipefail

repository=$(cd "$(dirname "$0")/../../.." && pwd)
kind_bin=${KIND_BIN:-kind}
istioctl_bin=${ISTIOCTL_BIN:-istioctl}
runtime=$(mktemp -d "${TMPDIR:-/tmp}/streamarr-mesh.XXXXXX")
cluster="streamarr-mesh-$$"
fixture_image="streamarr-mesh-fixture:$$"
export KUBECONFIG="$runtime/kubeconfig"

cleanup() {
  local result=$?
  trap - EXIT
  if [[ -f "$KUBECONFIG" ]]; then
    kubectl -n streamarr get pods -o wide > "$runtime/pods.txt" 2>&1 || true
    kubectl -n streamarr logs deployment/streamarr-server -c server > "$runtime/server.log" 2>&1 || true
    kubectl -n streamarr logs deployment/streamarr-server -c istio-proxy > "$runtime/proxy.log" 2>&1 || true
    "$kind_bin" delete cluster --name "$cluster" >> "$runtime/cleanup.log" 2>&1 || true
  fi
  docker image rm "$fixture_image" >> "$runtime/cleanup.log" 2>&1 || true
  echo "Mesh test evidence: $runtime"
  exit "$result"
}
trap cleanup EXIT

client() {
  local pod=$1
  shift
  kubectl -n streamarr exec "$pod" -c client -- \
    java --enable-native-access=ALL-UNNAMED -cp '/app/classes:/app/test-classes:/app/lib/*' \
    com.streamarr.server.fixtures.mesh.MeshValidationClient "$@"
}

expect_unprotected() {
  local pod=$1 expected=$2 host=$3
  local evidence="$runtime/red-$pod-$host.log"
  if client "$pod" "$expected" "$host" > "$evidence" 2>&1; then
    echo "The unprotected baseline unexpectedly denied $pod" >&2
    return 1
  fi
  grep -q 'Worker registration unexpectedly succeeded' "$evidence"
  echo "RED confirmed: $pod could register before mesh policies"
}

cd "$repository"
echo "Building the real server and scripted worker fixture"
./mvnw -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile="$runtime/classpath.txt" > "$runtime/build.log" 2>&1

"$kind_bin" create cluster --name "$cluster" --kubeconfig "$KUBECONFIG" \
  --image kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed \
  --wait 120s > "$runtime/cluster.log" 2>&1
"$istioctl_bin" install --set profile=minimal --set tag=1.31.0 \
  --set meshConfig.accessLogFile=/dev/stdout --skip-confirmation > "$runtime/istio-install.log" 2>&1

kubectl create --dry-run=client -f deploy/kubernetes/distributed-transcoding.yaml -o json \
  > "$runtime/deployment.json"
python3 deploy/kubernetes/test/prepare-fixture.py "$repository" "$runtime" "$fixture_image"
docker build -t "$fixture_image" "$runtime/image" > "$runtime/image.log" 2>&1
"$kind_bin" load docker-image "$fixture_image" --name "$cluster" >> "$runtime/image.log" 2>&1

kubectl apply -f "$runtime/bootstrap.json" > "$runtime/apply.log"
kubectl -n streamarr rollout status deployment/streamarr-server --timeout=180s
kubectl -n streamarr wait pod --all --for=condition=Ready --timeout=180s
client authorized-worker allowed streamarr-server | tee "$runtime/baseline-allowed.log"
expect_unprotected other-worker PERMISSION_DENIED streamarr-server
expect_unprotected unmeshed-worker UNAVAILABLE streamarr-server
server_ip=$(kubectl -n streamarr get pod -l app.kubernetes.io/name=streamarr-server \
  -o jsonpath='{.items[0].status.podIP}')
expect_unprotected unmeshed-worker UNAVAILABLE "$server_ip"

kubectl apply -f "$runtime/policies.json" >> "$runtime/apply.log"
# A fresh server proxy receives the policy in its initial configuration before accepting traffic.
kubectl -n streamarr rollout restart deployment/streamarr-server >> "$runtime/apply.log"
kubectl -n streamarr rollout status deployment/streamarr-server --timeout=120s
server_ip=$(kubectl -n streamarr get pod -l app.kubernetes.io/name=streamarr-server \
  -o jsonpath='{.items[0].status.podIP}')

client authorized-worker allowed streamarr-server | tee "$runtime/authorized.log"
client other-worker PERMISSION_DENIED streamarr-server | tee "$runtime/wrong-account.log"
client unmeshed-worker UNAVAILABLE streamarr-server | tee "$runtime/unmeshed-service.log"
client unmeshed-worker UNAVAILABLE "$server_ip" | tee "$runtime/unmeshed.log"
client unmeshed-worker http "$server_ip" | tee "$runtime/http-direct.log"
client other-worker http streamarr-server | tee "$runtime/http-service.log"
client authorized-worker allowed streamarr-server | tee "$runtime/authorized-after-denials.log"

kubectl apply -f deploy/kubernetes/test/existing-api-policy.yaml >> "$runtime/apply.log"
kubectl -n streamarr rollout restart deployment/streamarr-server >> "$runtime/apply.log"
kubectl -n streamarr rollout status deployment/streamarr-server --timeout=120s
client authorized-worker allowed streamarr-server | tee "$runtime/existing-api-allowed.log"
if client other-worker http streamarr-server > "$runtime/existing-api-denied.log" 2>&1; then
  echo "Worker policy broadened an existing HTTP authorization rule" >&2
  exit 1
fi
grep -q 'HTTP exchange failed: 403' "$runtime/existing-api-denied.log"

kubectl delete -f deploy/kubernetes/test/existing-api-policy.yaml >> "$runtime/apply.log"
kubectl apply -f deploy/kubernetes/test/existing-mtls-policy.yaml >> "$runtime/apply.log"
kubectl -n streamarr rollout restart deployment/streamarr-server >> "$runtime/apply.log"
kubectl -n streamarr rollout status deployment/streamarr-server --timeout=120s
server_ip=$(kubectl -n streamarr get pod -l app.kubernetes.io/name=streamarr-server \
  -o jsonpath='{.items[0].status.podIP}')
client authorized-worker allowed streamarr-server | tee "$runtime/existing-mtls-allowed.log"
client unmeshed-worker tls-required "$server_ip" | tee "$runtime/existing-mtls-denied.log"
"$istioctl_bin" proxy-status > "$runtime/proxy-status.txt"
echo "GREEN: worker identity enforced, plaintext bypass denied, existing API policies preserved"
