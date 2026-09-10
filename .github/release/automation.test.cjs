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

test('explains unavailable auto-merge metadata before publishing', async () => {
  const { github } = mergedReleaseFixture();
  await assert.rejects(runReleaseAutomation({
    repository: 'streamarr/streamarr-server', connect: async () => github, execute: () => 'null',
  }), /Cannot determine repository auto-merge setting.*allow_auto_merge="null"/);
  assert.equal(github.releases.length, 1);
  assert.deepEqual(github.opened, []);
});

test('prepares a stable release PR without requesting its merge', async () => {
  const fixture = mergedReleaseFixture();
  await (await fixture.manifest()).createReleases();
  const { github } = fixture;
  github.mergeCommitIterator = async function* () {
    yield { sha: 'f'.repeat(40), message: 'behavioral: improve playback', files: ['Example.java'] };
    yield { sha: 'e'.repeat(40), message: 'chore(main): release 0.0.12-SNAPSHOT', files: ['pom.xml'] };
    yield { sha: 'd'.repeat(40), message: 'chore(main): release 0.0.11', files: ['pom.xml'] };
  };
  const commands = [];
  const execute = (command, args) => {
    commands.push([command, ...args]);
    return args[1] === 'repos/streamarr/streamarr-server' ? 'true' : 'a'.repeat(40);
  };

  await runReleaseAutomation({ repository: 'streamarr/streamarr-server', connect: async () => github, execute });

  assert.equal(github.opened.length, 1);
  assert.equal(github.opened[0].title, 'chore(main): release 0.0.12');
  assert.deepEqual(github.opened[0].labels, ['autorelease: pending']);
  assert.equal(github.releases.length, 2);
  assert.equal(commands.some(command => command[1] === 'pr'), false);
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
    '--subject', 'chore(main): release 0.0.12-SNAPSHOT', '--body', '',
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
