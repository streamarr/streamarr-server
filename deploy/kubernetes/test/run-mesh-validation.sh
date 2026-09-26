#!/usr/bin/env bash
set -euo pipefail

worker_image=${STREAMARR_WORKER_IMAGE:?Set STREAMARR_WORKER_IMAGE to the verified standalone worker image}

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
    kubectl -n streamarr logs deployment/streamarr-transcode-worker -c worker > "$runtime/worker.log" 2>&1 || true
    kubectl -n streamarr logs deployment/streamarr-transcode-worker -c istio-proxy > "$runtime/worker-proxy.log" 2>&1 || true
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

server_pod_ip() {
  kubectl -n streamarr get pods,endpointslices -o json \
    | python3 deploy/kubernetes/test/ready-pod-ip.py
}

restart_server() {
  # A fresh proxy receives the policy before accepting traffic.
  kubectl -n streamarr rollout restart deployment/streamarr-server >> "$runtime/apply.log"
  kubectl -n streamarr rollout status deployment/streamarr-server --timeout=120s
}

prepare_cluster() {
  echo "Building the worker-session service and scripted authorization clients"
  ./mvnw -q -DskipTests test-compile dependency:build-classpath \
    -Dmdep.includeScope=test -Dmdep.outputFile="$runtime/classpath.txt" > "$runtime/build.log" 2>&1

  "$kind_bin" create cluster --name "$cluster" --kubeconfig "$KUBECONFIG" \
    --image kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed \
    --wait 120s > "$runtime/cluster.log" 2>&1
  "$istioctl_bin" install --set profile=minimal --set tag=1.31.0 \
    --set meshConfig.accessLogFile=/dev/stdout --skip-confirmation > "$runtime/istio-install.log" 2>&1

  python3 deploy/kubernetes/render-deployment.py > "$runtime/deployment.yaml"
  kubectl create --dry-run=client -f "$runtime/deployment.yaml" -o json \
    > "$runtime/deployment.json"
  python3 deploy/kubernetes/test/prepare-fixture.py "$repository" "$runtime" "$fixture_image"
  docker build -t "$fixture_image" "$runtime/image" > "$runtime/image.log" 2>&1
  "$kind_bin" load docker-image "$fixture_image" --name "$cluster" >> "$runtime/image.log" 2>&1
}

verify_unprotected_connections() {
  kubectl apply -f "$runtime/bootstrap.json" > "$runtime/apply.log"
  kubectl -n streamarr rollout status deployment/streamarr-server --timeout=180s
  kubectl -n streamarr wait pod --all --for=condition=Ready --timeout=180s
  client authorized-worker allowed streamarr-server | tee "$runtime/baseline-allowed.log"
  client other-worker allowed streamarr-server | tee "$runtime/baseline-other-account.log"
  client unmeshed-worker allowed streamarr-server | tee "$runtime/baseline-unmeshed-service.log"
  server_ip=$(server_pod_ip)
  client unmeshed-worker allowed "$server_ip" | tee "$runtime/baseline-unmeshed-pod.log"
}

verify_worker_policy() {
  kubectl apply -f "$runtime/policies.json" >> "$runtime/apply.log"
  restart_server
  server_ip=$(server_pod_ip)

  client authorized-worker allowed streamarr-server | tee "$runtime/authorized.log"
  client other-worker PERMISSION_DENIED streamarr-server | tee "$runtime/wrong-account.log"
  client unmeshed-worker UNAVAILABLE streamarr-server | tee "$runtime/unmeshed-service.log"
  # Direct Pod-IP traffic has no automatic mTLS. Prove the Service routes to this exact Pod.
  client authorized-worker registered streamarr-server | tee "$runtime/authorized-pod.log"
  test "$(server_pod_ip)" = "$server_ip"
  client unmeshed-worker UNAVAILABLE "$server_ip" | tee "$runtime/unmeshed.log"
  client authorized-worker registered streamarr-server | tee "$runtime/authorized-pod-after-denial.log"
  test "$(server_pod_ip)" = "$server_ip"
  client unmeshed-worker http "$server_ip" | tee "$runtime/http-direct.log"
  client other-worker http streamarr-server | tee "$runtime/http-service.log"
  client authorized-worker allowed streamarr-server | tee "$runtime/authorized-after-denials.log"
}

verify_existing_http_policy() {
  kubectl apply -f deploy/kubernetes/test/existing-api-policy.yaml >> "$runtime/apply.log"
  restart_server
  client authorized-worker allowed streamarr-server | tee "$runtime/existing-api-allowed.log"
  client other-worker http-forbidden http://streamarr-server:8080/health \
    | tee "$runtime/existing-api-denied.log"
}

verify_existing_mtls_policy() {
  kubectl delete -f deploy/kubernetes/test/existing-api-policy.yaml >> "$runtime/apply.log"
  kubectl apply -f deploy/kubernetes/test/existing-mtls-policy.yaml >> "$runtime/apply.log"
  restart_server
  server_ip=$(server_pod_ip)
  client authorized-worker allowed streamarr-server | tee "$runtime/existing-mtls-allowed.log"
  client authorized-worker http streamarr-server | tee "$runtime/existing-mtls-http-control.log"
  test "$(server_pod_ip)" = "$server_ip"
  client unmeshed-worker tls-required "http://$server_ip:8080/health" | tee "$runtime/existing-mtls-denied.log"
  client authorized-worker http streamarr-server | tee "$runtime/existing-mtls-http-after-denial.log"
  test "$(server_pod_ip)" = "$server_ip"
}

verify_uploaded_segment() {
  kubectl -n streamarr exec authorized-worker -c client -- cat /tmp/mesh-segment.mp4 \
    > "$runtime/mesh-segment.mp4"
  kubectl -n streamarr exec -i "$worker_pod" -c worker -- sh -c 'cat > /tmp/mesh-segment.mp4' \
    < "$runtime/mesh-segment.mp4"
  kubectl -n streamarr exec "$worker_pod" -c worker -- /cnb/lifecycle/launcher \
    ffprobe -v error -show_streams -of json -o /tmp/mesh-segment.json /tmp/mesh-segment.mp4 \
    > "$runtime/decode.log" 2>&1
  kubectl -n streamarr exec "$worker_pod" -c worker -- cat /tmp/mesh-segment.json \
    > "$runtime/mesh-segment.json"
  python3 - "$runtime/mesh-segment.json" <<'PY'
import json
import sys
streams = json.load(open(sys.argv[1]))["streams"]
assert any(s.get("codec_type") == "video" and s.get("codec_name") == "h264"
         and s.get("width") == 320 and s.get("height") == 180 for s in streams), streams
assert any(s.get("codec_type") == "audio" and s.get("codec_name") == "aac" for s in streams), streams
PY
  kubectl -n streamarr exec "$worker_pod" -c worker -- /cnb/lifecycle/launcher \
    ffmpeg -v error -xerror -i /tmp/mesh-segment.mp4 -f null - >> "$runtime/decode.log" 2>&1
}

verify_real_worker() {
  docker image inspect "$worker_image" > "$runtime/worker-image.json"
  "$kind_bin" load docker-image "$worker_image" --name "$cluster" >> "$runtime/image.log" 2>&1
  kubectl apply -f "$runtime/worker.json" >> "$runtime/apply.log"
  kubectl -n streamarr rollout status deployment/streamarr-transcode-worker --timeout=180s
  worker_pod=$(kubectl -n streamarr get pod -l app.kubernetes.io/name=streamarr-transcode-worker \
    -o jsonpath='{.items[0].metadata.name}')
  client authorized-worker worker-health mesh-worker-health | tee "$runtime/worker-health.log"
  client authorized-worker media streamarr-server | tee "$runtime/media.log"
  verify_uploaded_segment
  client other-worker PERMISSION_DENIED streamarr-server | tee "$runtime/media-wrong-account.log"
  client unmeshed-worker UNAVAILABLE streamarr-server | tee "$runtime/media-unmeshed-service.log"
  client authorized-worker registered streamarr-server | tee "$runtime/media-pod-control.log"
  test "$(server_pod_ip)" = "$server_ip"
  client unmeshed-worker UNAVAILABLE "$server_ip" | tee "$runtime/media-unmeshed-pod.log"
  client authorized-worker registered streamarr-server | tee "$runtime/media-pod-after-denial.log"
  test "$(server_pod_ip)" = "$server_ip"
  echo "GREEN: real worker Actuator probes, media probe, and decoded HLS upload through Istio"
}

cd "$repository"
prepare_cluster
verify_unprotected_connections
verify_worker_policy
verify_existing_http_policy
verify_existing_mtls_policy
verify_real_worker
"$istioctl_bin" proxy-status > "$runtime/proxy-status.txt"
echo "GREEN: worker identity enforced, plaintext bypass denied, existing API policies preserved"
