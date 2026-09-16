"""Exercise image-selection failures before deployment commands can run."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parents[3]


class WorkerImageConfigurationTests(unittest.TestCase):
    def test_renderer_rejects_missing_worker_image(self):
        environment = dict(os.environ)
        environment.pop("STREAMARR_WORKER_IMAGE", None)

        result = subprocess.run(
            ["python3", str(REPOSITORY / "deploy/kubernetes/render-deployment.py")],
            env=environment, capture_output=True, text=True, timeout=10, check=False,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("STREAMARR_WORKER_IMAGE", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_renderer_preserves_explicit_worker_image(self):
        reference = "streamarr/streamarr-transcode-worker@sha256:" + "a" * 64
        environment = dict(os.environ, STREAMARR_WORKER_IMAGE=reference)

        result = subprocess.run(
            ["python3", str(REPOSITORY / "deploy/kubernetes/render-deployment.py")],
            env=environment, capture_output=True, text=True, timeout=10, check=False,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("image: " + json.dumps(reference), result.stdout)
        self.assertNotIn("${STREAMARR_WORKER_IMAGE}", result.stdout)
        self.assertIn("kind: AuthorizationPolicy", result.stdout)

    def test_mesh_validation_rejects_missing_image_before_building_or_creating_a_cluster(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = root / "deploy/kubernetes/test/run-mesh-validation.sh"
            script.parent.mkdir(parents=True)
            shutil.copyfile(REPOSITORY / "deploy/kubernetes/test/run-mesh-validation.sh", script)
            build = root / "mvnw"
            build.write_text("#!/bin/sh\necho 'Unexpected build before image validation' >&2\nexit 93\n")
            build.chmod(0o755)
            docker = root / "docker"
            docker.write_text("#!/bin/sh\nexit 0\n")
            docker.chmod(0o755)
            environment = dict(os.environ, TMPDIR=temporary, PATH=temporary + ":" + os.environ["PATH"])
            environment.pop("STREAMARR_WORKER_IMAGE", None)
            environment.pop("WORKER_IMAGE", None)

            result = subprocess.run(
                ["bash", str(script)], env=environment, capture_output=True,
                text=True, timeout=10, check=False,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("STREAMARR_WORKER_IMAGE", result.stderr)
            self.assertNotIn("Unexpected build", result.stderr)
            self.assertEqual(list(root.glob("streamarr-mesh.*")), [])


if __name__ == "__main__":
    unittest.main()
