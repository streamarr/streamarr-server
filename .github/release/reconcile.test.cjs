const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { Manifest } = require('release-please');
const { PullRequestBody } = require('release-please/build/src/util/pull-request-body');
const { Version } = require('release-please/build/src/version');
const { reconcile } = require('./reconcile.cjs');
require('./metadata.cjs');
const github = { async *pullRequestIterator() {} };

function mergedReleaseFixture(overrides = {}) {
  const root = path.resolve(__dirname, '../..');
  const pullRequest = {
    number: 42, title: 'behavioral: release 0.0.11',
    body: new PullRequestBody([
      { version: Version.parse('0.0.11'), notes: '## [0.0.11]\n\n### Bug fixes\n\n* Fix playback' },
    ]).toString(),
    labels: ['autorelease: pending'], sha: 'd'.repeat(40),
    headBranchName: 'release-please--branches--main', baseBranchName: 'main', files: ['pom.xml'],
    ...overrides,
  };
  const github = {
    releases: [{ tagName: 'v0.0.10', sha: 'a'.repeat(40), notes: '' }],
    opened: [],
    repository: { owner: 'streamarr', repo: 'streamarr-server' },
    getFileJson: async file => file === '.release-please-manifest.json'
      ? { '.': '0.0.11' }
      : JSON.parse(fs.readFileSync(path.join(root, file), 'utf8')),
    getFileContentsOnBranch: async file => ({ parsedContent: fs.readFileSync(path.join(root, file), 'utf8') }),
    findFilesByFilenameAndRef: async () => ['pom.xml'],
    async *pullRequestIterator(_branch, state) {
      if (state === 'MERGED') yield pullRequest;
    },
    async *releaseIterator() {
      yield* this.releases;
    },
    async *mergeCommitIterator() {
      yield { sha: pullRequest.sha, message: 'behavioral: release 0.0.11', files: ['pom.xml'], pullRequest };
      yield { sha: 'c'.repeat(40), message: 'behavioral: release 0.0.11-SNAPSHOT', files: ['pom.xml'] };
      yield { sha: 'b'.repeat(40), message: 'fix: correct playback', files: ['Example.java'] };
      yield { sha: 'a'.repeat(40), message: 'behavioral: release 0.0.10', files: ['pom.xml'] };
    },
    async createRelease(release) {
      const result = { tagName: release.tag.toString(), sha: release.sha, notes: release.notes };
      this.releases.unshift(result);
      return result;
    },
    async removeIssueLabels(labels) {
      pullRequest.labels = pullRequest.labels.filter(label => !labels.includes(label));
    },
    async addIssueLabels(labels) { pullRequest.labels.push(...labels); },
    async commentOnIssue() {},
    async createPullRequest(candidate) {
      const result = { ...candidate, number: 43 };
      this.opened.push(result);
      return result;
    },
  };
  return { github, manifest: () => Manifest.fromManifest(github, 'main') };
}

test('fails visibly when a merged release title cannot be recognized', async () => {
  const fixture = mergedReleaseFixture({ title: 'Edited release title' });
  const queued = [];
  await assert.rejects(
    reconcile({ ...fixture, enqueue: async pr => queued.push(pr) }),
    /Unprocessed merged release PR #42/,
  );
  assert.deepEqual(queued, []);
});

test('fails visibly when a merged release body cannot be parsed', async () => {
  const fixture = mergedReleaseFixture({ body: 'Edited release notes without release metadata' });
  const queued = [];
  await assert.rejects(
    reconcile({ ...fixture, enqueue: async pr => queued.push(pr) }),
    /Unprocessed merged release PR #42/,
  );
  assert.deepEqual(queued, []);
});

test('publishes a valid merged release and queues its next snapshot', async () => {
  const fixture = mergedReleaseFixture();
  const queued = [];
  await reconcile({ ...fixture, enqueue: async pr => queued.push(pr) });
  assert.equal(fixture.github.releases[0].tagName, 'v0.0.11');
  assert.equal(fixture.github.releases[0].sha, 'd'.repeat(40));
  assert.equal(queued.length, 1);
  assert.equal(queued[0].title, 'behavioral: release 0.0.12-SNAPSHOT');
  assert.deepEqual(queued[0].labels, ['autorelease: snapshot']);
});

test('queues the refreshed release PR after processing merged releases', async () => {
  const state = { released: false, queued: [] };
  const pr = { number: 42, sha: 'a'.repeat(40), title: 'chore(main): release 1.2.3' };
  const manifest = async () => ({
    createReleases: async () => { state.released = true; },
    createPullRequests: async () => {
      assert.equal(state.released, true);
      return [undefined, pr];
    },
  });

  await reconcile({ github, manifest, enqueue: async candidate => state.queued.push(candidate) });

  assert.deepEqual(state.queued, [pr]);
});

test('queues nothing when the release PR is already current', async () => {
  const state = { queued: [] };
  const manifest = async () => ({
    createReleases: async () => [],
    createPullRequests: async () => [],
  });
  await reconcile({ github, manifest, enqueue: async pr => state.queued.push(pr) });
  assert.deepEqual(state.queued, []);
});

test('stops before preparing another PR when tagging fails', async () => {
  const state = { queued: [], prepared: false };
  const manifest = async () => ({
    createReleases: async () => { throw new Error('tag creation failed'); },
    createPullRequests: async () => { state.prepared = true; return []; },
  });
  await assert.rejects(
    reconcile({ github, manifest, enqueue: async pr => state.queued.push(pr) }),
    /tag creation failed/,
  );
  assert.deepEqual(state, { queued: [], prepared: false });
});
