const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const { test } = require('node:test');
const { runReleaseAutomation } = require('./automation.cjs');
const { mergedReleaseFixture } = require('./release-fixture.cjs');

test('explains missing repository configuration when invoked from the CLI', () => {
  const env = { ...process.env };
  delete env.GITHUB_REPOSITORY;
  const result = spawnSync(process.execPath, [path.join(__dirname, 'run.cjs')], { env, encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /GITHUB_REPOSITORY must be set/);
});

test('stops before publishing when repository auto-merge is disabled', async () => {
  const { github } = mergedReleaseFixture();
  await assert.rejects(runReleaseAutomation({
    repository: 'streamarr/streamarr-server', connect: async () => github, execute: () => 'false',
  }), /Enable repository auto-merge/);
  assert.equal(github.releases.length, 1);
  assert.deepEqual(github.opened, []);
});

test('queues the updated PR head with its release title and an empty squash body', async () => {
  const { github } = mergedReleaseFixture();
  const commands = [];
  const head = 'e'.repeat(40);
  const execute = (command, args) => {
    commands.push([command, ...args]);
    if (args[1] === 'repos/streamarr/streamarr-server') return 'true';
    if (args[1] === 'repos/streamarr/streamarr-server/pulls/43') return head;
    return '';
  };
  await runReleaseAutomation({ repository: 'streamarr/streamarr-server', connect: async () => github, execute });
  assert.equal(github.releases[0].tagName, 'v0.0.11');
  assert.deepEqual(commands.at(-1), [
    'gh', 'pr', 'merge', '43', '--repo', 'streamarr/streamarr-server',
    '--auto', '--squash', '--match-head-commit', head,
    '--subject', 'behavioral: release 0.0.12-SNAPSHOT', '--body', '',
  ]);
});

test('does not request a merge when the updated PR has no valid head revision', async () => {
  const { github } = mergedReleaseFixture();
  const commands = [];
  const execute = (command, args) => {
    commands.push([command, ...args]);
    return args[1] === 'repos/streamarr/streamarr-server' ? 'true' : 'missing';
  };
  await assert.rejects(runReleaseAutomation({
    repository: 'streamarr/streamarr-server', connect: async () => github, execute,
  }), /Release PR has no head revision/);
  assert.equal(commands.some(command => command[1] === 'pr'), false);
});
