const assert = require('node:assert/strict');
const { execFileSync, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test } = require('node:test');
const YAML = require('yaml');

const root = path.resolve(__dirname, '../..');
const workflow = YAML.parse(fs.readFileSync(path.join(root, '.github/workflows/publish-release.yml'), 'utf8'));
const validation = workflow.jobs.validate_release.steps.find(step => step.id === 'release');

function checkout(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'release-validation-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const git = args => execFileSync('git', args, {
    cwd: directory, encoding: 'utf8',
    env: {
      ...process.env, GIT_AUTHOR_NAME: 'Release fixture', GIT_AUTHOR_EMAIL: 'fixture@example.test',
      GIT_COMMITTER_NAME: 'Release fixture', GIT_COMMITTER_EMAIL: 'fixture@example.test',
      GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: os.devNull,
    },
  }).trim();
  git(['init', '--quiet', '--initial-branch=main']);
  git(['-c', 'commit.gpgsign=false', 'commit', '--quiet', '--allow-empty', '-m', 'fixture']);
  git(['tag', 'v1.2.3']);
  git(['update-ref', 'refs/remotes/origin/main', 'HEAD']);
  fs.mkdirSync(path.join(directory, '.github/release'), { recursive: true });
  fs.copyFileSync(path.join(__dirname, 'verify-version.cjs'), path.join(directory, '.github/release/verify-version.cjs'));
  fs.writeFileSync(path.join(directory, 'mvnw'), '#!/bin/sh\nprintf 1.2.3\n', { mode: 0o755 });
  return { directory, git };
}

function runStep(step, options) {
  const flags = step.shell === 'bash' ? ['-e', '-o', 'pipefail'] : ['-e'];
  return spawnSync('bash', [...flags, '-c', step.run], { encoding: 'utf8', ...options });
}

function validate(directory) {
  return runStep(validation, {
    cwd: directory,
    env: { ...process.env, RELEASE_TAG: 'v1.2.3', GITHUB_OUTPUT: path.join(directory, 'outputs') },
  });
}

test('reports a tag revision mismatch before building release images', t => {
  const { directory, git } = checkout(t);
  git(['-c', 'commit.gpgsign=false', 'commit', '--quiet', '--allow-empty', '-m', 'later revision']);
  const result = validate(directory);
  assert.equal(result.status, 1);
  assert.match(result.stdout + result.stderr, /Release tag does not match the checked-out revision/);
  assert.equal(fs.existsSync(path.join(directory, 'outputs')), false);
});

test('reports a release outside main before building release images', t => {
  const { directory, git } = checkout(t);
  git(['-c', 'commit.gpgsign=false', 'commit', '--quiet', '--allow-empty', '-m', 'unmerged revision']);
  git(['tag', '--force', 'v1.2.3']);
  const result = validate(directory);
  assert.equal(result.status, 1);
  assert.match(result.stdout + result.stderr, /Release revision is not an ancestor of main/);
  assert.equal(fs.existsSync(path.join(directory, 'outputs')), false);
});

test('exports the validated version and revision for a tag on main', t => {
  const { directory, git } = checkout(t);
  const result = validate(directory);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(fs.readFileSync(path.join(directory, 'outputs'), 'utf8'),
    `version=1.2.3\nrevision=${git(['rev-parse', 'HEAD'])}\n`);
});
