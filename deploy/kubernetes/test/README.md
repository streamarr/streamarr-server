# Worker mesh validation

This test creates a disposable kind cluster. It uses a separate kubeconfig and deletes its cluster
and fixture image on exit. Existing clusters and the current Kubernetes context remain unchanged.

The fixture runs the real `WorkerSessionServer`. A scripted worker registers through the published
protocol and returns a correlated probe result. This checks transport authorization. It does not
run FFmpeg or validate the future standalone worker image.

The test proves:

- Before mesh policy is applied, both a different ServiceAccount and an unmeshed Pod can register.
  The denial assertions fail for the expected reason.
- After policy is applied, the dedicated worker ServiceAccount can register and complete a probe.
- Another meshed ServiceAccount receives gRPC `PERMISSION_DENIED`.
- An unmeshed client using the authorized ServiceAccount cannot bypass mTLS through either the
  Service or the direct Pod IP.
- Ordinary HTTP remains accessible both through the Service and directly through the Pod IP.
- Existing HTTP authorization and namespace-wide strict mTLS policies remain effective.

The HTTP endpoint is a test sentinel. The deployment contract tests separately verify the actual
Actuator probe paths and ports.

Use Java 25, the repository's Node toolchain, Docker, Python 3, and kubectl. Download official
[kind 0.33.0](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0) and
[Istio 1.31.0](https://github.com/istio/istio/releases/tag/1.31.0) binaries into a temporary directory.
Verify them against the release checksum files. The test pins Kubernetes 1.36.4 and the Java fixture
image by digest. Kubernetes 1.36 is supported by this Istio release.

```sh
KIND_BIN=/absolute/temporary/path/kind \
ISTIOCTL_BIN=/absolute/temporary/path/istioctl \
bash deploy/kubernetes/test/run-mesh-validation.sh
```

The script prints the temporary evidence directory. It contains the RED and GREEN client outputs,
the exact policies taken from the deployment manifest, and the server and proxy logs.

The policy follows Istio's
[port-level peer authentication](https://istio.io/latest/docs/reference/config/security/peer_authentication/)
and [authenticated workload principals](https://istio.io/latest/docs/concepts/security/).
Port 9090 requires strict mTLS and denies principals other than the worker ServiceAccount.
Other ports inherit their existing authentication and authorization policies.
