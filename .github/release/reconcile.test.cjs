const assert = require('node:assert/strict');
const { test } = require('node:test');
const { reconcile } = require('./reconcile.cjs');
const { mergedReleaseFixture } = require('./release-fixture.cjs');
const github = { async *pullRequestIterator() {} };

test('prepares a merged release as an unpublished GitHub draft', async () => {
  const fixture = mergedReleaseFixture();
  await (await fixture.manifest()).createReleases();
  assert.equal(fixture.github.releases[0].draft, true);
  assert.equal(fixture.github.releases[0].tagName, 'v0.0.11');
  assert.equal(fixture.github.releases[0].sha, 'd'.repeat(40));
});

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

test('tags a draft release and queues its next snapshot before publication', async () => {
  const fixture = mergedReleaseFixture();
  const queued = [];
  await reconcile({ ...fixture, enqueue: async pr => queued.push(pr) });
  assert.equal(fixture.github.releases[0].tagName, 'v0.0.11');
  assert.equal(fixture.github.releases[0].sha, 'd'.repeat(40));
  assert.equal(fixture.github.releases[0].draft, true);
  assert.deepEqual(fixture.github.tags[0], { name: 'v0.0.11', sha: 'd'.repeat(40) });
  assert.equal(queued.length, 1);
  assert.equal(queued[0].title, 'chore(main): release 0.0.12-SNAPSHOT');
  assert.deepEqual(queued[0].labels, ['autorelease: snapshot']);
});

test('keeps the same Maven baseline when the prepared draft is published', async () => {
  const fixture = mergedReleaseFixture();
  await (await fixture.manifest()).createReleases();
  const [before] = await (await fixture.manifest()).buildPullRequests();
  fixture.github.releases[0].draft = false;
  assert.deepEqual(await (await fixture.manifest()).createReleases(), []);
  const [after] = await (await fixture.manifest()).buildPullRequests();
  assert.equal(before.title.getVersion().toString(), '0.0.12-SNAPSHOT');
  assert.equal(after.title.getVersion().toString(), '0.0.12-SNAPSHOT');
  assert.equal(fixture.github.releases.length, 2);
  assert.deepEqual(fixture.github.tags[0], { name: 'v0.0.11', sha: 'd'.repeat(40) });
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
