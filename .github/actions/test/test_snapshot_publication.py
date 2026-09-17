import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ACTIONS = Path(__file__).resolve().parents[1]
IMAGE = 'streamarr/streamarr-server'
REVISION = 'a' * 40
AMD64_DIGEST = 'sha256:' + '1' * 64
ARM64_DIGEST = 'sha256:' + '2' * 64
INDEX_DIGEST = 'sha256:' + '3' * 64


class SnapshotPublicationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.registry = self.root / 'registry.json'
        self.registry.write_text(json.dumps({
            'indexDigest': INDEX_DIGEST,
            'images': {
                f'{IMAGE}@{AMD64_DIGEST}': [{
                    'digest': AMD64_DIGEST,
                    'platform': {'os': 'linux', 'architecture': 'amd64'},
                }],
                f'{IMAGE}@{ARM64_DIGEST}': [{
                    'digest': ARM64_DIGEST,
                    'platform': {'os': 'linux', 'architecture': 'arm64'},
                }],
            },
        }))
        for architecture, digest in [('amd64', AMD64_DIGEST), ('arm64', ARM64_DIGEST)]:
            (self.root / f'{architecture}-image.json').write_text(json.dumps({
                'sourceRevision': REVISION,
                'architecture': architecture,
                'image': f'{IMAGE}@{digest}',
            }))
        self.environment = dict(os.environ,
            PATH=str(ACTIONS / 'test' / 'fixtures') + os.pathsep + os.environ['PATH'],
            FAKE_REGISTRY=str(self.registry),
            FAKE_MAIN_REVISION=REVISION,
            GITHUB_SHA=REVISION,
            GITHUB_EVENT_NAME='push',
            GITHUB_REF='refs/heads/main',
            GITHUB_REPOSITORY='streamarr/streamarr-server',
        )
        self.version('1.2.3-SNAPSHOT')

    def version(self, value):
        (self.root / 'pom.xml').write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<parent><version>9.0.0</version></parent>'
            f'<version>{value}</version></project>')

    def publish(self):
        return subprocess.run(
            ['bash', str(ACTIONS / 'publish-snapshot.sh')],
            cwd=self.root, env=self.environment, capture_output=True, text=True, timeout=10)

    def images(self):
        return json.loads(self.registry.read_text())['images']

    def test_shouldPublishCurrentMavenSnapshotWhenVerifiedMainImagesAreAvailable(self):
        result = self.publish()

        self.assertEqual(result.returncode, 0, result.stderr)
        images = self.images()
        expected = [
            {'digest': AMD64_DIGEST, 'platform': {'os': 'linux', 'architecture': 'amd64'}},
            {'digest': ARM64_DIGEST, 'platform': {'os': 'linux', 'architecture': 'arm64'}},
        ]
        self.assertEqual(images[f'{IMAGE}:1.2.3-SNAPSHOT'], expected)
        self.assertEqual(images[f'{IMAGE}:sha-{REVISION}'], expected)
        self.assertEqual(set(images), {
            f'{IMAGE}@{AMD64_DIGEST}', f'{IMAGE}@{ARM64_DIGEST}',
            f'{IMAGE}@{INDEX_DIGEST}', f'{IMAGE}:sha-{REVISION}', f'{IMAGE}:1.2.3-SNAPSHOT',
        })

    def test_shouldKeepSnapshotUnchangedWhenAnOlderMainRunIsRetried(self):
        self.environment['FAKE_MAIN_REVISION'] = 'b' * 40
        state = json.loads(self.registry.read_text())
        state['images'][f'{IMAGE}:1.2.3-SNAPSHOT'] = [{'digest': 'sha256:' + '9' * 64}]
        self.registry.write_text(json.dumps(state))

        result = self.publish()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.images()[f'{IMAGE}:1.2.3-SNAPSHOT'], [{'digest': 'sha256:' + '9' * 64}])
        self.assertIn(f'{IMAGE}:sha-{REVISION}', self.images())

    def test_shouldLeaveStablePublicationToReleaseWorkflowWhenMavenVersionIsStable(self):
        self.version('1.2.3')

        result = self.publish()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(f'{IMAGE}:1.2.3', self.images())
        self.assertNotIn(f'{IMAGE}:1.2.3-SNAPSHOT', self.images())
        self.assertIn(f'{IMAGE}:sha-{REVISION}', self.images())

    def test_shouldRejectMalformedVersionsWhenSelectingSnapshotTag(self):
        for version in ['1.2.3-SNAPSHOT-SNAPSHOT', '1.2-SNAPSHOT', '01.2.3-SNAPSHOT']:
            with self.subTest(version=version):
                self.version(version)
                result = self.publish()

                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn('Maven version', result.stderr)
                self.assertNotIn(f'{IMAGE}:{version}', self.images())

    def test_shouldRejectMismatchedNativeReceiptWhenPreparingMultiArchitectureImage(self):
        for field, value in [('sourceRevision', 'b' * 40), ('architecture', 'amd64'),
                             ('image', f'{IMAGE}:unverified')]:
            with self.subTest(field=field):
                receipt = {'sourceRevision': REVISION, 'architecture': 'arm64',
                           'image': f'{IMAGE}@{ARM64_DIGEST}'}
                receipt[field] = value
                (self.root / 'arm64-image.json').write_text(json.dumps(receipt))

                result = self.publish()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Invalid native image receipt', result.stderr)
                self.assertNotIn(f'{IMAGE}:sha-{REVISION}', self.images())

    def test_shouldRejectUntrustedPublicationWhenInvocationIsNotReviewedMain(self):
        for key, value in [('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/feature'),
                           ('GITHUB_REPOSITORY', 'fork/streamarr-server'), ('GITHUB_SHA', 'short')]:
            with self.subTest(key=key):
                previous = self.environment[key]
                self.environment[key] = value
                result = self.publish()
                self.environment[key] = previous

                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Image publication requires', result.stderr)
                self.assertNotIn(f'{IMAGE}:1.2.3-SNAPSHOT', self.images())

    def test_shouldWithholdSnapshotWhenGitHubCannotResolveMain(self):
        self.environment['FAKE_GITHUB_FAILURE'] = 'true'

        result = self.publish()

        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(f'{IMAGE}:1.2.3-SNAPSHOT', self.images())

    def test_shouldWithholdSnapshotWhenPublishedIndexIsMissingAnArchitecture(self):
        for architecture, digest in [('amd64', AMD64_DIGEST), ('arm64', ARM64_DIGEST)]:
            with self.subTest(architecture=architecture):
                state = json.loads(self.registry.read_text())
                state['indexOverride'] = state['images'][f'{IMAGE}@{digest}']
                self.registry.write_text(json.dumps(state))

                result = self.publish()

                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(f'{IMAGE}:1.2.3-SNAPSHOT', self.images())

    def test_shouldRejectInvalidIndexDigestWhenRegistryReportsPublication(self):
        state = json.loads(self.registry.read_text())
        state['indexDigest'] = 'sha256:invalid'
        self.registry.write_text(json.dumps(state))

        result = self.publish()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('manifest digest', result.stderr)
        self.assertNotIn(f'{IMAGE}:1.2.3-SNAPSHOT', self.images())

    def test_shouldFailClosedWhenGitHubReturnsAnInvalidMainRevision(self):
        self.environment['FAKE_MAIN_REVISION'] = 'null'

        result = self.publish()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('main revision', result.stderr)
        self.assertNotIn(f'{IMAGE}:1.2.3-SNAPSHOT', self.images())
