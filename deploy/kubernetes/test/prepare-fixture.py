#!/usr/bin/env python3
"""Package compiled test fixtures and reuse the deployment's real mesh resources."""

import copy
import json
from pathlib import Path
import shutil
import sys


def package_image(repository, output):
    context = output / "image"
    (context / "lib").mkdir(parents=True)
    for directory in ("classes", "test-classes"):
        shutil.copytree(repository / "target" / directory, context / directory)

    classpath = (output / "classpath.txt").read_text().strip().split(":")
    for number, entry in enumerate(classpath):
        shutil.copyfile(entry, context / "lib" / f"{number}.jar")

    shutil.copyfile(repository / "deploy/kubernetes/test/Dockerfile", context / "Dockerfile")


def read_resources(path):
    serialized = path.read_text().strip()
    resources = []
    decoder = json.JSONDecoder()
    # kubectl emits one JSON object for each YAML document.
    while serialized:
        resource, consumed = decoder.raw_decode(serialized)
        resources.append(resource)
        serialized = serialized[consumed:].lstrip()

    return resources


def server_fixture(resources, image):
    server = copy.deepcopy(next(
        resource for resource in resources
        if resource["kind"] == "Deployment" and resource["metadata"]["name"] == "streamarr-server"
    ))
    server_pod = server["spec"]["template"]["spec"]
    server_container = server_pod["containers"][0]
    server_container["image"] = image
    server_container["imagePullPolicy"] = "Never"
    server_container["command"] = [
        "java", "--enable-native-access=ALL-UNNAMED", "-cp",
        "/app/classes:/app/test-classes:/app/lib/*",
        "com.streamarr.server.fixtures.mesh.MeshValidationServer",
    ]
    server_container.pop("env", None)
    server_container.pop("volumeMounts", None)
    server_pod.pop("volumes", None)
    for probe in ("startupProbe", "livenessProbe", "readinessProbe"):
        server_container[probe]["httpGet"]["path"] = "/health"

    return server


def client_fixtures(image, security_context):
    clients = [{
        "apiVersion": "v1", "kind": "ServiceAccount",
        "metadata": {"name": "streamarr-untrusted", "namespace": "streamarr"},
    }]
    for name, account, injected in (
        ("authorized-worker", "streamarr-transcode-worker", True),
        ("other-worker", "streamarr-untrusted", True),
        ("unmeshed-worker", "streamarr-transcode-worker", False),
    ):
        clients.append({
            "apiVersion": "v1", "kind": "Pod",
            "metadata": {
                "name": name, "namespace": "streamarr",
                "labels": {"app": name},
                "annotations": {
                    "sidecar.istio.io/inject": str(injected).lower(),
                    "proxy.istio.io/config": '{"holdApplicationUntilProxyStarts":true}',
                },
            },
            "spec": {
                "serviceAccountName": account,
                "containers": [{
                    "name": "client", "image": image, "imagePullPolicy": "Never",
                    "command": ["sleep", "infinity"],
                    "securityContext": security_context,
                    "resources": {
                        "requests": {"cpu": "100m", "memory": "128Mi"},
                        "limits": {"memory": "384Mi"},
                    },
                }],
            },
        })

    return clients


def worker_fixture(resources, image, worker_image):
    worker = copy.deepcopy(next(
        resource for resource in resources
        if resource["kind"] == "Deployment" and resource["metadata"]["name"] == "streamarr-transcode-worker"
    ))
    worker["spec"]["replicas"] = 1
    worker_pod = worker["spec"]["template"]["spec"]
    worker_container = worker_pod["containers"][0]
    worker_container["image"] = worker_image
    worker_container["imagePullPolicy"] = "Never"
    worker_pod["securityContext"] = {"fsGroup": 1000}
    worker_pod["volumes"] = [{"name": "media", "emptyDir": {}}]
    worker_pod["initContainers"] = [{
        "name": "media-fixture", "image": image, "imagePullPolicy": "Never",
        "command": [
            "cp", "/app/test-classes/BigBuckBunny_320x180_10s.mp4", "/fixture/mesh-fixture.mkv",
        ],
        "securityContext": worker_container["securityContext"],
        "volumeMounts": [{"name": "media", "mountPath": "/fixture"}],
    }]
    health_service = {
        "apiVersion": "v1", "kind": "Service",
        "metadata": {"name": "mesh-worker-health", "namespace": "streamarr"},
        "spec": {
            "selector": worker["spec"]["selector"]["matchLabels"],
            "ports": [{"name": "http-health", "port": 9091, "targetPort": "http-health"}],
        },
    }
    return [health_service, worker]


def write_resources(path, resources):
    manifest = {"apiVersion": "v1", "kind": "List", "items": resources}
    path.write_text(json.dumps(manifest, indent=2) + "\n")


def main():
    repository, output, image = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    worker_image = sys.argv[4]
    package_image(repository, output)
    resources = read_resources(output / "deployment.json")
    server = server_fixture(resources, image)
    bootstrap = [resource for resource in resources if resource["kind"] in ("Namespace", "ServiceAccount", "Service")]
    bootstrap.append(server)
    security_context = server["spec"]["template"]["spec"]["containers"][0]["securityContext"]
    bootstrap.extend(client_fixtures(image, security_context))
    policies = [resource for resource in resources if resource["kind"] in ("PeerAuthentication", "AuthorizationPolicy")]
    write_resources(output / "bootstrap.json", bootstrap)
    write_resources(output / "policies.json", policies)
    write_resources(output / "worker.json", worker_fixture(resources, image, worker_image))


if __name__ == "__main__":
    main()
