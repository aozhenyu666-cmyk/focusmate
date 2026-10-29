#!/usr/bin/env node
/**
 * Push the committed working tree to GitHub through the REST API.
 *
 * Why not `git push`: on this machine TCP to github.com:443 is blocked
 * ("Failed to connect to github.com:443"), while api.github.com works.
 * So we build blobs -> tree -> commit -> move the branch ref, which is
 * equivalent to a normal push, just over a different transport.
 *
 * Usage:
 *   node scripts/push.mjs
 *   node scripts/push.mjs --message "docs: update acceptance"
 *
 * Requires: gh auth status logged in with repo scope.
 * Commit locally first (git add/commit); this uploads what `git ls-files` lists.
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = process.env.FOCUSMATE_REPO ?? 'aozhenyu666-cmyk/focusmate';
const BRANCH = process.env.FOCUSMATE_BRANCH ?? 'main';
const API = `https://api.github.com/repos/${REPO}`;
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const argMessage = (() => {
  const i = process.argv.indexOf('--message');
  return i >= 0 ? process.argv[i + 1] : undefined;
})();

const git = (args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' });

async function gh(path, init = {}) {
  const token = execFileSync('gh', ['auth', 'token'], { encoding: 'utf8' }).trim();
  if (!token) throw new Error('gh is not logged in; run: gh auth login');
  const res = await fetch(`${API}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'User-Agent': 'focusmate-push',
      'Content-Type': 'application/json',
      ...(init.headers ?? {}),
    },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${init.method ?? 'GET'} ${path} -> ${res.status} ${text.slice(0, 400)}`);
  return text ? JSON.parse(text) : {};
}

const message = argMessage ?? git(['log', '-1', '--pretty=%B']).trim();
if (!message) throw new Error('no commit message; pass --message or commit first');

const remoteRef = await gh(`/git/ref/heads/${BRANCH}`);
const parentSha = remoteRef.object.sha;

const files = git(['ls-files']).split('\n').map((s) => s.trim()).filter(Boolean);
const tree = [];
for (const rel of files) {
  const abs = resolve(root, rel);
  if (!existsSync(abs)) continue;
  const blob = await gh('/git/blobs', {
    method: 'POST',
    body: JSON.stringify({ content: readFileSync(abs).toString('base64'), encoding: 'base64' }),
  });
  tree.push({ path: rel, mode: rel === 'gradlew' ? '100755' : '100644', type: 'blob', sha: blob.sha });
}
console.log(`uploaded ${tree.length} file objects`);

// 用全量 tree 而不是 base_tree 增量：增量树要删文件得额外塞 sha:null 条目，
// 一旦漏了就会出现"本地已删、远端还在"。这个仓库很小，全量更不容易出错。
const newTree = await gh('/git/trees', {
  method: 'POST',
  body: JSON.stringify({ tree }),
});
const commit = await gh('/git/commits', {
  method: 'POST',
  body: JSON.stringify({ message, tree: newTree.sha, parents: [parentSha] }),
});
await gh(`/git/refs/heads/${BRANCH}`, {
  method: 'PATCH',
  body: JSON.stringify({ sha: commit.sha, force: false }),
});

console.log(`pushed to https://github.com/${REPO}/tree/${BRANCH}`);
console.log(`commit: ${commit.sha}`);
console.log(`parent: ${parentSha}`);
