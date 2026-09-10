const assert = require('node:assert/strict');
const { test } = require('node:test');
const { reconcile } = require('./reconcile.cjs');

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

  await reconcile({ manifest, enqueue: async candidate => state.queued.push(candidate) });

  assert.deepEqual(state.queued, [pr]);
});

test('queues nothing when the release PR is already current', async () => {
  const state = { queued: [] };
  const manifest = async () => ({
    createReleases: async () => [],
    createPullRequests: async () => [],
  });
  await reconcile({ manifest, enqueue: async pr => state.queued.push(pr) });
  assert.deepEqual(state.queued, []);
});

test('stops before preparing another PR when tagging fails', async () => {
  const state = { queued: [], prepared: false };
  const manifest = async () => ({
    createReleases: async () => { throw new Error('tag creation failed'); },
    createPullRequests: async () => { state.prepared = true; return []; },
  });
  await assert.rejects(
    reconcile({ manifest, enqueue: async pr => state.queued.push(pr) }),
    /tag creation failed/,
  );
  assert.deepEqual(state, { queued: [], prepared: false });
});
