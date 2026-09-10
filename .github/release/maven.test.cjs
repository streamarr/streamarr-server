const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { Manifest } = require('release-please');
const { buildStrategy } = require('release-please/build/src/factory');
const { TagName } = require('release-please/build/src/util/tag-name');
const { Version } = require('release-please/build/src/version');
require('./metadata.cjs');

const root = path.resolve(__dirname, '../..');
const quiet = { info() {}, warn() {}, error() {}, debug() {}, trace() {} };
const github = {
  repository: { owner: 'streamarr', repo: 'streamarr-server' },
  getFileJson: async file => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8')),
  getFileContentsOnBranch: async file => ({ parsedContent: fs.readFileSync(path.join(root, file), 'utf8') }),
  findFilesByFilenameAndRef: async () => ['pom.xml'],
};

async function strategy() {
  const manifest = await Manifest.fromManifest(github, 'main');
  return buildStrategy({
    ...manifest.repositoryConfig['.'], github, targetBranch: 'main', logger: quiet,
  });
}

async function prepare(commits, latestRelease) {
  const history = {
    ...github,
    async *releaseIterator() {
      yield { tagName: latestRelease.tag.toString(), sha: latestRelease.sha, notes: latestRelease.notes };
    },
    async *mergeCommitIterator() {
      yield* commits;
      yield { sha: latestRelease.sha, message: 'Initial release', files: ['pom.xml'] };
    },
    async *pullRequestIterator() {},
  };
  const manifest = await Manifest.fromManifest(history, 'main', undefined, undefined, { logger: quiet });
  const [pullRequest] = await manifest.buildPullRequests();
  return pullRequest;
}

function release(version) {
  return { tag: new TagName(Version.parse(version)), sha: 'a'.repeat(40), notes: '' };
}

test('bootstraps from the published version with an automatic Maven snapshot PR', async () => {
  const pr = await prepare([], release('0.0.10'));
  assert.equal(pr.title.getVersion().toString(), '0.0.11-SNAPSHOT');
  assert.equal(pr.title.toString(), 'chore(main): release 0.0.11-SNAPSHOT');
  assert.deepEqual(pr.labels, ['autorelease: snapshot']);
  assert.doesNotMatch(pr.body.toString(), /robot|beep|generated|authored|co-authored/i);
  assert.match(pr.body.toString(), /Maven version/);
  const update = pr.updates.find(candidate => candidate.path === 'pom.xml');
  const pom = '<project><parent><version>4.1.1</version></parent><version>0.0.1-SNAPSHOT</version></project>';
  const updated = update.updater.updateContent(pom);
  assert.equal(updated, pom.replace('<version>0.0.1-SNAPSHOT</version>', '<version>0.0.11-SNAPSHOT</version>'));
});

for (const [message, expected] of [
  ['fix: correct playback', '0.0.11'],
  ['feat: add playlists', '0.1.0'],
  ['feat!: replace playback protocol', '1.0.0'],
  ['behavioral: preserve legacy release changes', '0.0.11'],
  ['behavioral: release 1.2.3', '0.0.11'],
  ['behavioral: release 4k playback sessions', '0.0.11'],
  ['behavioral: release 2 stale locks', '0.0.11'],
  ['behavioral: release 1.2.3 hotfix', '0.0.11'],
  ['behavioral: release lock 1.2.3', '0.0.11'],
  ['behavioral: release jellyfin-ffmpeg 7.1.2', '0.0.11'],
  ['behavioral(playback): release 1.2.3', '0.0.11'],
  ['behavioral: introduce playlists\n\nAdds playlist management.\n\nfeat: add playlists', '0.1.0'],
  ['behavioral: replace playback\n\nUpdates the playback protocol.\n\nBREAKING CHANGE: playback clients must migrate', '1.0.0'],
  ['behavioral: introduce playlists\n\nfeat: add playlists\n\nAdditional explanation after the feature.', '0.1.0'],
  ['behavioral: replace playback\n\nBREAKING CHANGE: playback clients must migrate\n\nAdditional explanation after the footer.', '1.0.0'],
]) {
  test(`keeps the POM and tag aligned for ${message}`, async () => {
    const maven = await strategy();
    const commits = [
      {
        sha: 'b'.repeat(40), message, files: ['src/main/java/Example.java'],
        pullRequest: { title: message.split('\n')[0], body: '' },
      },
      { sha: 'c'.repeat(40), message: 'chore(main): release 0.0.11-SNAPSHOT', files: ['pom.xml'] },
    ];
    const pr = await prepare(commits, release('0.0.10'));
    assert.equal(pr.title.getVersion().toString(), expected);
    assert.doesNotMatch(pr.body.toString(), /robot|beep|generated|authored|co-authored/i);
    assert.match(pr.body.toString(), /Maven version/);
    const pom = '<project><parent><version>4.1.1</version></parent><version>0.0.11-SNAPSHOT</version></project>';
    const updated = pr.updates.find(update => update.path === 'pom.xml').updater.updateContent(pom);
    assert.equal(updated, pom.replace('0.0.11-SNAPSHOT', expected));
    const tagged = await maven.buildRelease({
      title: pr.title.toString(), body: pr.body.toString(), headBranchName: pr.headRefName,
      baseBranchName: 'main', sha: 'd'.repeat(40), number: 42, labels: ['autorelease: pending'], files: ['pom.xml'],
    });
    assert.equal(tagged.tag.toString(), `v${expected}`);
    assert.equal(tagged.sha, 'd'.repeat(40));
  });
}

test('does not publish a snapshot PR as a GitHub release', async () => {
  const maven = await strategy();
  const pr = await prepare([], release('0.0.10'));
  const tagged = await maven.buildRelease({
    title: pr.title.toString(), body: pr.body.toString(), headBranchName: pr.headRefName,
    baseBranchName: 'main', sha: 'd'.repeat(40), number: 42, labels: pr.labels, files: ['pom.xml'],
  });
  assert.equal(tagged, undefined);
});

test('does not create a release loop from the snapshot version commit alone', async () => {
  const commits = [
    { sha: 'c'.repeat(40), message: 'chore(main): release 0.0.11-SNAPSHOT', files: ['pom.xml'] },
  ];
  assert.equal(await prepare(commits, release('0.0.10')), undefined);
});
