const fs = require('node:fs');
const path = require('node:path');
const { Manifest } = require('release-please');
const { PullRequestBody } = require('release-please/build/src/util/pull-request-body');
const { Version } = require('release-please/build/src/version');
require('./metadata.cjs');

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
    releases: [{ tagName: 'v0.0.10', sha: 'a'.repeat(40), notes: '', draft: false }],
    tags: [{ name: 'v0.0.10', sha: 'a'.repeat(40) }],
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
      yield* this.releases.filter(release => this.tags.some(tag => tag.name === release.tagName));
    },
    async *tagIterator() { yield* this.tags; },
    async *mergeCommitIterator() {
      yield { sha: pullRequest.sha, message: 'behavioral: release 0.0.11', files: ['pom.xml'], pullRequest };
      yield { sha: 'c'.repeat(40), message: 'behavioral: release 0.0.11-SNAPSHOT', files: ['pom.xml'] };
      yield { sha: 'b'.repeat(40), message: 'fix: correct playback', files: ['Example.java'] };
      yield { sha: 'a'.repeat(40), message: 'behavioral: release 0.0.10', files: ['pom.xml'] };
    },
    async createRelease(release, { draft = false, forceTag = false } = {}) {
      const result = { tagName: release.tag.toString(), sha: release.sha, notes: release.notes, draft };
      if (!draft || forceTag) this.tags.unshift({ name: result.tagName, sha: result.sha });
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

module.exports = { mergedReleaseFixture };
