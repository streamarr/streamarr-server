#!/usr/bin/env python3
"""Select the live Pod that is also the server Service's sole ready endpoint."""

import json
import sys

resources = json.load(sys.stdin)["items"]
pods = [pod for pod in resources
        if pod["kind"] == "Pod"
        and pod["metadata"].get("labels", {}).get("app.kubernetes.io/name") == "streamarr-server"
        and not pod["metadata"].get("deletionTimestamp")
        and pod.get("status", {}).get("podIP")
        and any(condition["type"] == "Ready" and condition["status"] == "True"
                for condition in pod["status"].get("conditions", []))]
if len(pods) != 1:
    sys.exit(f"Expected one ready server Pod, found {len(pods)}")

address = pods[0]["status"]["podIP"]
endpoints = {address for resource in resources
             if resource["kind"] == "EndpointSlice"
             and resource["metadata"].get("labels", {}).get("kubernetes.io/service-name") == "streamarr-server"
             for endpoint in resource["endpoints"]
             if endpoint.get("conditions", {}).get("ready") is True
             and not endpoint.get("conditions", {}).get("terminating")
             for address in endpoint["addresses"]}
if endpoints != {address}:
    sys.exit(f"Service endpoint {endpoints} does not match ready server Pod {address}")
print(address)
