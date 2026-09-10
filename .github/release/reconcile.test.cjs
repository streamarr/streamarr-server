const assert = require('node:assert/strict');
const { test } = require('node:test');
const { reconcile } = require('./reconcile.cjs');
const { mergedReleaseFixture } = require('./release-fixture.cjs');
const github = { async *pullRequestIterator() {} };


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
