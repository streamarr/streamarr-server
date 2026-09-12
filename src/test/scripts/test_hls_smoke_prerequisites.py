"""Exercise the existing smoke test through Maven with an isolated executable PATH."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


CHECKOUT = Path(__file__).resolve().parents[3]
SMOKE_CLASS = "com.streamarr.server.services.streaming.HlsStreamingSmokeTest"


class HlsSmokePrerequisitesTest(unittest.TestCase):
    def run_smoke(self, *, ffmpeg_available):
        scenario = "ffmpeg-only" if ffmpeg_available else "neither-tool"
        output = CHECKOUT / "target" / "review-repros" / scenario
        output.mkdir(parents=True, exist_ok=True)
        report = CHECKOUT / "target" / "surefire-reports" / ("TEST-" + SMOKE_CLASS + ".xml")
        report.unlink(missing_ok=True)
        node = shutil.which("node")
        self.assertIsNotNone(node, "Select the repository's Node.js toolchain first")

        with tempfile.TemporaryDirectory(prefix="streamarr-smoke-path-") as temporary:
            binaries = Path(temporary)
            (binaries / "node").symlink_to(node)
            if ffmpeg_available:
                ffmpeg = binaries / "ffmpeg"
                ffmpeg.write_text("#!/bin/sh\nprintf 'ffmpeg prerequisite fixture\\n'\n")
                ffmpeg.chmod(0o755)

            environment = os.environ.copy()
            environment["PATH"] = os.pathsep.join((str(binaries), "/usr/bin", "/bin"))
            self.assertIsNone(shutil.which("ffprobe", path=environment["PATH"]))
            self.assertEqual(
                shutil.which("ffmpeg", path=environment["PATH"]) is not None,
                ffmpeg_available,
            )
            result = subprocess.run(
                [
                    str(CHECKOUT / "mvnw"),
                    "-B",
                    "-Dtest=" + SMOKE_CLASS + "#shouldDetectCorrectCodecsWhenProbingTestVideo",
                    "-Dsurefire.excludedGroups=",
                    "-Djacoco.skip=true",
                    "test",
                ],
                cwd=CHECKOUT,
                env=environment,
                capture_output=True,
                text=True,
                timeout=120,
            )
        (output / "maven.log").write_text(result.stdout + result.stderr)
        self.assertTrue(report.exists(), "Maven did not reach the smoke test: " + str(output))
        shutil.copy2(report, output / report.name)
        suite = ET.parse(report).getroot()
        errors = "\n".join(element.text or "" for element in suite.findall("testcase/error"))
        return result, suite, errors

    def test_should_skip_when_neither_tool_is_available(self):
        result, suite, errors = self.run_smoke(ffmpeg_available=False)
        self.assertEqual(int(suite.attrib["errors"]), 0, errors)
        self.assertEqual(result.returncode, 0)
        self.assertGreater(int(suite.attrib["skipped"]), 0)

    def test_should_skip_when_ffmpeg_is_available_but_ffprobe_is_missing(self):
        result, suite, errors = self.run_smoke(ffmpeg_available=True)
        self.assertEqual(
            int(suite.attrib["errors"]),
            0,
            "Missing ffprobe must skip the suite before setup, not produce an error:\n" + errors,
        )
        self.assertEqual(result.returncode, 0)
        self.assertGreater(int(suite.attrib["skipped"]), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
