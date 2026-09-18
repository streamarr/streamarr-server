"""Select the same live server endpoint for allowed and denied mesh requests."""

import copy
import json
from pathlib import Path
import subprocess
import unittest

SCRIPT = Path(__file__).with_name("ready-pod-ip.py")
READY = {"kind": "Pod", "metadata": {"labels": {"app.kubernetes.io/name": "streamarr-server"}}, "status": {"podIP": "10.0.0.2",
         "conditions": [{"type": "Ready", "status": "True"}]}}
ENDPOINTS = {"kind": "EndpointSlice", "metadata": {"labels": {"kubernetes.io/service-name": "streamarr-server"}},
             "endpoints": [{"addresses": ["10.0.0.2"], "conditions": {"ready": True}}]}


class ReadyPodTests(unittest.TestCase):
    def test_selects_ready_replacement_when_terminating_pod_is_first(self):
        terminating = copy.deepcopy(READY)
        terminating["metadata"]["deletionTimestamp"] = "2026-09-18T12:00:00Z"
        terminating["status"]["podIP"] = "10.0.0.1"
        pending = copy.deepcopy(READY)
        pending.pop("status")
        result = self.select([terminating, pending, READY])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "10.0.0.2\n")

    def test_rejects_missing_or_ambiguous_live_target(self):
        not_ready = copy.deepcopy(READY)
        not_ready["status"] = {"podIP": "10.0.0.1"}
        for pods in [[], [not_ready], [READY, READY]]:
            with self.subTest(pods=pods):
                result = self.select(pods)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")

    def test_rejects_service_routed_to_different_or_unready_endpoint(self):
        for endpoints in [[], [{"addresses": ["10.0.0.1"], "conditions": {"ready": True}}],
                          [{"addresses": ["10.0.0.2"], "conditions": {"ready": False}}]]:
            with self.subTest(endpoints=endpoints):
                service = copy.deepcopy(ENDPOINTS)
                service["endpoints"] = endpoints
                result = self.select([READY], service)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Service endpoint", result.stderr)
                self.assertEqual(result.stdout, "")

    def select(self, pods, endpoints=ENDPOINTS):
        return subprocess.run(["python3", str(SCRIPT)], input=json.dumps({"items": pods + [endpoints]}),
                              capture_output=True, text=True, timeout=5, check=False)


if __name__ == "__main__":
    unittest.main()
