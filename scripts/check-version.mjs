#!/usr/bin/env node
// Fails the build if the cache-busting version is inconsistent anywhere —
// index.html's ?v= strings, sw.js's CACHE name, and sw.js's PRECACHE list.
// Forgetting to run scripts/bump-version.mjs after touching the JS/CSS is
// what wedges users on a stale cached copy, so CI enforces it instead of
// trusting people to remember.
//
// Usage: node scripts/check-version.mjs   (or: npm run check-version)

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const index = readFileSync(join(root, 'index.html'), 'utf8');
const sw = readFileSync(join(root, 'sw.js'), 'utf8');

let failed = false;
const complain = msg => { console.error('✗ ' + msg); failed = true; };
const ok = msg => console.log('✓ ' + msg);

// 1. One version, everywhere in index.html.
const versions = [...index.matchAll(/\?v=(\d+)/g)].map(m => Number(m[1]));
if (!versions.length) complain('no ?v=N version string found in index.html');
else if (new Set(versions).size !== 1) complain(`index.html mixes versions: ${[...new Set(versions)].join(', ')}`);
else ok(`index.html consistent at v${versions[0]}`);
const v = versions[0];

// 2. sw.js agrees: cache name, comment, and every ?v= in the file.
const cacheMatch = sw.match(/journal-v(\d+)/);
if (!cacheMatch) complain('sw.js has no journal-vN cache name');
else if (Number(cacheMatch[1]) !== v) complain(`sw.js cache is v${cacheMatch[1]} but index.html is v${v}`);
else ok(`sw.js cache name matches v${v}`);

const swVersions = [...sw.matchAll(/\?v=(\d+)/g)].map(m => Number(m[1]));
const badSw = swVersions.filter(n => n !== v);
if (badSw.length) complain(`sw.js precache mixes versions: ${[...new Set(swVersions)].join(', ')}`);
else ok('sw.js precache URLs all at v' + v);

// 3. Every script/link the app loads at ?v=N is in the PRECACHE list.
for (const m of index.matchAll(/(?:src|href)="(\.\/)?(css\/app\.css|js\/[a-z]+\.js)\?v=\d+"/g)) {
  const path = m[2];
  if (!sw.includes(`./${path}?v=${v}`)) complain(`sw.js PRECACHE is missing ./${path}?v=${v}`);
  else ok(`PRECACHE covers ./${path}?v=${v}`);
}

if (failed) {
  console.error('\nCache-bust versions are out of lockstep. Run: npm run bump-version');
  process.exit(1);
}
console.log('\nCache-bust versions in lockstep.');
