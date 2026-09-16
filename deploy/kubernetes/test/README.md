# Worker mesh validation

This test creates a disposable kind cluster. It uses a separate kubeconfig and deletes its cluster
and fixture image on exit. Existing clusters and the current Kubernetes context remain unchanged.

The fixture runs the real `WorkerSessionServer`. Scripted workers first isolate the transport
authorization checks. `STREAMARR_WORKER_IMAGE` is required for the standalone Spring worker and
real media execution. Missing image configuration fails before creating any resources.
The image must already be present in the local Docker daemon. This test never publishes
an image.

The test proves:

- Before mesh policy is applied, both a different ServiceAccount and an unmeshed Pod can register.
  The denial assertions fail for the expected reason.
- After policy is applied, the dedicated worker ServiceAccount can register and complete a probe.
- Another meshed ServiceAccount receives gRPC `PERMISSION_DENIED`.
- An unmeshed client using the authorized ServiceAccount cannot bypass mTLS through either the
  Service or the direct Pod IP.
- Ordinary HTTP remains accessible both through the Service and directly through the Pod IP.
- Existing HTTP authorization and namespace-wide strict mTLS policies remain effective.

The server HTTP endpoints are test fixtures, not a full Spring server. The deployment contract
tests separately verify the server's Actuator probe paths and ports.

Use Java 25, Docker, Python 3, and kubectl. Download official
[kind 0.33.0](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0) and
[Istio 1.31.0](https://github.com/istio/istio/releases/tag/1.31.0) binaries into a temporary directory.
Verify them against the release checksum files. The test pins Kubernetes 1.36.4 and the Java fixture
image by digest. Kubernetes 1.36 is supported by this Istio release.

```sh
STREAMARR_WORKER_IMAGE=your-worker-image:tested \
KIND_BIN=/absolute/temporary/path/kind \
ISTIOCTL_BIN=/absolute/temporary/path/istioctl \
bash deploy/kubernetes/test/run-mesh-validation.sh
```

Use a verified local image during draft validation and the published immutable digest for the
final cutover. The harness always deploys one worker using the shipped Deployment's normal command,
environment, security context, and Actuator probes. It replaces the image and media volume with a
read-only copy of the repository's Big Buck Bunny fixture.
It adds a test-only health Service so that the meshed client can check Actuator through Istio's
service discovery while namespace-wide strict mTLS is active.

After the scripted clients have disconnected, the image phase verifies:

- Both worker Actuator health groups report `UP` after the session is accepted.
- The worker probes the fixture as MP4 with H.264 video at 320 × 180.
- A full transcode uploads an HLS segment through the real server upload endpoint. FFprobe checks
  its H.264 video dimensions and AAC audio. FFmpeg decodes the segment with errors treated as fatal.
- The wrong ServiceAccount and unmeshed Service and Pod IP connections remain denied while the
  real worker is connected.

The script prints the temporary evidence directory. It contains the RED and GREEN client outputs,
the exact policies taken from the deployment manifest, and the server and proxy logs. It
also retains the local image identity, worker logs, uploaded segment, and decode results. Cleanup
removes the disposable fixture image but leaves the supplied worker image intact.

The policy follows Istio's
[port-level peer authentication](https://istio.io/latest/docs/reference/config/security/peer_authentication/)
and [authenticated workload principals](https://istio.io/latest/docs/concepts/security/).
Port 9090 requires strict mTLS and denies principals other than the worker ServiceAccount.
Other ports inherit their existing authentication and authorization policies.
