#!/usr/bin/env node
// Bumps the app's cache-busting version in lockstep across index.html and
// sw.js. Run this before every commit that touches css/app.css, js/app.js,
// or js/boot.js — bumping it in only one or two of the three places is what
// wedges users on a stale cached copy, which is exactly why the app has a
// "Clear cache and reload" recovery banner in the first place.
//
// Usage: node scripts/bump-version.mjs   (or: npm run bump-version)

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const indexPath = join(root, 'index.html');
const swPath = join(root, 'sw.js');

let index = readFileSync(indexPath, 'utf8');
let sw = readFileSync(swPath, 'utf8');

const match = index.match(/\?v=(\d+)/);
if (!match) {
  console.error('Could not find a ?v=N version string in index.html — nothing bumped.');
  process.exit(1);
}
const current = Number(match[1]);
const next = current + 1;

index = index.replace(/\?v=\d+/g, `?v=${next}`);
sw = sw
  .replace(/Service Worker v\d+/, `Service Worker v${next}`)
  .replace(/journal-v\d+/g, `journal-v${next}`)
  .replace(/\?v=\d+/g, `?v=${next}`);

writeFileSync(indexPath, index);
writeFileSync(swPath, sw);

console.log(`Bumped v${current} -> v${next} in index.html and sw.js.`);
console.log('Double-check sw.js\'s PRECACHE list picked up every entry before committing.');
