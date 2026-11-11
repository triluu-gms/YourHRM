#!/usr/bin/env node
// YourHRM sample data (LOCAL DEVELOPMENT ONLY): creates a realistic data set through the backend REST API so every
// frontend screen built so far has something to show. Prerequisites: scripts/dev-reset.ps1 (or the steps in
// scripts/sample-data/README.md) and the backend running with SPRING_PROFILES_ACTIVE=dev.
//
//   node scripts/sample-data/seed.mjs            # everything
//   node scripts/sample-data/seed.mjs core       # Core only
//   node scripts/sample-data/seed.mjs people     # People only (Core must exist)
import { CFG, client, failureCount } from './lib.mjs';
import { seedCore } from './core.mjs';

const only = process.argv[2];
const owner = client(CFG.owner);
const ctx = {};

console.log(`YourHRM sample data -> ${CFG.api}`);
await seedCore(owner, ctx);
if (only !== 'core') {
  const { seedPeople } = await import('./people.mjs');
  await seedPeople(owner, ctx, only === 'people');
}
const failed = failureCount();
console.log(failed ? `\nDone with ${failed} failed step(s) - see ✗ above.` : '\nDone, all steps succeeded.');
process.exitCode = failed ? 1 : 0;
