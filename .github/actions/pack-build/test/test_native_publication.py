import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ACTION = Path(__file__).resolve().parents[1]
IMAGE = 'streamarr/streamarr-server'
DIGEST = 'sha256:' + '1' * 64


class NativePublicationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.state = self.root / 'docker.json'
        self.state.write_text(json.dumps({
            'local': {'local-image': {'digest': DIGEST}}, 'registry': {},
        }))
        self.environment = dict(os.environ,
            PATH=str(ACTION / 'test' / 'fixtures') + os.pathsep + os.environ['PATH'],
            FAKE_DOCKER=str(self.state),
            GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
            GIT_AUTHOR_NAME='Image fixture', GIT_AUTHOR_EMAIL='fixture@example.test',
            GIT_COMMITTER_NAME='Image fixture', GIT_COMMITTER_EMAIL='fixture@example.test',
            GITHUB_SHA='b' * 40,
        )
        self.git('init', '--quiet')
        self.git('commit', '--quiet', '--allow-empty', '-m', 'validated release')
        self.revision = self.git('rev-parse', 'HEAD')

    def git(self, *arguments):
        result = subprocess.run(['git', '-c', 'commit.gpgsign=false', *arguments],
            cwd=self.root, env=self.environment, capture_output=True, text=True, check=True)
        return result.stdout.strip()

    def publish(self, architecture='amd64'):
        return subprocess.run(['bash', str(ACTION / 'publish-native-image.sh'),
            'local-image', architecture], cwd=self.root, env=self.environment,
            capture_output=True, text=True, timeout=10)

    def test_shouldRecordPublishedDigestWhenTheCheckedOutRevisionDiffersFromTheWorkflow(self):
        result = self.publish()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((self.root / 'amd64-image.json').read_text()), {
            'sourceRevision': self.revision, 'architecture': 'amd64',
            'image': f'{IMAGE}@{DIGEST}',
        })
        self.assertEqual(json.loads(self.state.read_text())['registry'], {
            f'{IMAGE}:sha-{self.revision}-amd64': {'digest': DIGEST},
        })

    def test_shouldWithholdReceiptWhenNativePushFails(self):
        self.environment['PUSH_FAILURE'] = 'true'

        result = self.publish()

        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertFalse((self.root / 'amd64-image.json').exists())
        self.assertEqual(json.loads(self.state.read_text())['registry'], {})

    def test_shouldWithholdReceiptWhenTheRegistryDigestIsInvalid(self):
        self.state.write_text(json.dumps({
            'local': {'local-image': {'digest': 'invalid'}}, 'registry': {},
        }))

        result = self.publish()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('digest', result.stderr)
        self.assertFalse((self.root / 'amd64-image.json').exists())

    def test_shouldRejectPublicationWhenTheArchitectureIsUnsupported(self):
        result = self.publish('s390x')

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('architecture', result.stderr)
        self.assertEqual(json.loads(self.state.read_text())['registry'], {})
