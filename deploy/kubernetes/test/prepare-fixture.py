#!/usr/bin/env python3
"""Package compiled test fixtures and reuse the deployment's real mesh resources."""

import copy
import json
from pathlib import Path
import shutil
import sys

repository, output, image = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
context = output / "image"
(context / "lib").mkdir(parents=True)
for directory in ("classes", "test-classes"):
    shutil.copytree(repository / "target" / directory, context / directory)
for number, entry in enumerate((output / "classpath.txt").read_text().strip().split(":")):
    shutil.copyfile(entry, context / "lib" / f"{number}.jar")

shutil.copyfile(repository / "deploy/kubernetes/test/Dockerfile", context / "Dockerfile")
serialized = (output / "deployment.json").read_text().strip()
resources = []
decoder = json.JSONDecoder()
# kubectl emits one JSON object for each YAML document.
while serialized:
    resource, consumed = decoder.raw_decode(serialized)
    resources.append(resource)
    serialized = serialized[consumed:].lstrip()
policies = [r for r in resources if r["kind"] in ("PeerAuthentication", "AuthorizationPolicy")]
bootstrap = [r for r in resources if r["kind"] in ("Namespace", "ServiceAccount", "Service")]
server = copy.deepcopy(next(r for r in resources if r["kind"] == "Deployment"
                            and r["metadata"]["name"] == "streamarr-server"))
pod = server["spec"]["template"]["spec"]
container = pod["containers"][0]
container["image"] = image
container["imagePullPolicy"] = "Never"
container["command"] = ["java", "--enable-native-access=ALL-UNNAMED", "-cp",
                        "/app/classes:/app/test-classes:/app/lib/*",
                        "com.streamarr.server.fixtures.mesh.MeshValidationServer"]
container.pop("env", None)
container.pop("volumeMounts", None)
pod.pop("volumes", None)
for probe in ("startupProbe", "livenessProbe", "readinessProbe"):
    container[probe]["httpGet"]["path"] = "/health"
bootstrap.append(server)
bootstrap.append({"apiVersion": "v1", "kind": "ServiceAccount",
                  "metadata": {"name": "streamarr-untrusted", "namespace": "streamarr"}})
for name, account, injected in (
        ("authorized-worker", "streamarr-transcode-worker", True),
        ("other-worker", "streamarr-untrusted", True),
        ("unmeshed-worker", "streamarr-transcode-worker", False)):
    bootstrap.append({
        "apiVersion": "v1", "kind": "Pod",
        "metadata": {"name": name, "namespace": "streamarr",
                     "labels": {"app": name},
                     "annotations": {"sidecar.istio.io/inject": str(injected).lower(),
                                     "proxy.istio.io/config": '{"holdApplicationUntilProxyStarts":true}'}},
        "spec": {"serviceAccountName": account,
                 "containers": [{"name": "client", "image": image, "imagePullPolicy": "Never",
                                 "command": ["sleep", "infinity"],
                                 "securityContext": container["securityContext"],
                                 "resources": {"requests": {"cpu": "100m", "memory": "128Mi"},
                                               "limits": {"memory": "384Mi"}}}]}})

for name, items in (("bootstrap", bootstrap), ("policies", policies)):
    (output / f"{name}.json").write_text(json.dumps({"apiVersion": "v1", "kind": "List",
                                                  "items": items}, indent=2) + "\n")
