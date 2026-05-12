#!/usr/bin/env node
//
// MIL-STD-2525 SVG generator for OmniTAK Mobile.
//
// Reads the canonical CoT type catalogue from
// `../../app/src/main/assets/cot_types.json`, generates a 96×96 SVG
// per SIDC via the open-source `milsymbol` library, and writes the
// output to `../../app/src/main/assets/milstd/<sidc>.svg`. The same
// SVGs are mirrored into the iOS bundle by hand.
//
// Run with `npm install && npm run generate` from this directory.
// Idempotent: re-running overwrites the asset bundle in place.

import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ms from 'milsymbol';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');
const catalogPath = path.join(repoRoot, 'app/src/main/assets/cot_types.json');
const outDir = path.join(repoRoot, 'app/src/main/assets/milstd');

const SIZE_PX = 96;

async function main() {
    const catalogue = JSON.parse(await fs.readFile(catalogPath, 'utf8'));
    await fs.mkdir(outDir, { recursive: true });

    let written = 0;
    let skipped = 0;
    const failures = [];

    for (const def of catalogue) {
        const sidc = def.sidc.replace(/\.svg$/i, '');
        const outPath = path.join(outDir, `${sidc}.svg`);
        try {
            // milsymbol expects a 15-character SIDC; pad with dashes
            // if the catalogue stored a shorter form (defensive).
            const padded = sidc.padEnd(15, '-').slice(0, 15);
            const symbol = new ms.Symbol(padded, { size: SIZE_PX });
            const svg = symbol.asSVG();
            if (!svg || svg.length < 50) {
                throw new Error(`milsymbol produced empty SVG for ${padded}`);
            }
            await fs.writeFile(outPath, svg, 'utf8');
            written += 1;
        } catch (e) {
            failures.push({ sidc, value: def.value, error: String(e) });
            skipped += 1;
        }
    }

    console.log(`✓ wrote ${written} SVGs to ${path.relative(repoRoot, outDir)}/`);
    if (skipped > 0) {
        console.warn(`✗ skipped ${skipped} entries:`);
        for (const f of failures) {
            console.warn(`    ${f.sidc} (${f.value}): ${f.error}`);
        }
    }
}

main().catch((e) => {
    console.error('generate.mjs failed:', e);
    process.exit(1);
});
