/**
 * Bundle size report for the UI refresh (docs/design/ui-refresh.md): the gzipped size of every
 * built JS and CSS chunk, plus font files listed separately because they are already compressed and
 * do not belong in the script budget even though they are on the wire.
 *
 * Run through `pnpm ui:bundle`, which builds first. Writes `reports/bundle-size.json` next to the
 * Lighthouse reports (gitignored) and prints a table, so every PR can quote its delta against the
 * same baseline.
 */
import { gzipSync } from "node:zlib";
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { extname, join, relative, resolve } from "node:path";

const DIST = resolve(process.env.SAIMAN_BUNDLE_DIST ?? "dist");
const OUT = resolve("reports/bundle-size.json");
const FONT_EXTENSIONS = new Set([".woff", ".woff2", ".ttf", ".otf"]);
const SCRIPT_EXTENSIONS = new Set([".js", ".mjs", ".css"]);

function walk(dir) {
    return readdirSync(dir).flatMap((entry) => {
        const path = join(dir, entry);
        return statSync(path).isDirectory() ? walk(path) : [path];
    });
}

const files = walk(DIST).map((path) => {
    const raw = readFileSync(path);
    return {
        file: relative(DIST, path),
        ext: extname(path),
        bytes: raw.byteLength,
        gzip: gzipSync(raw, { level: 9 }).byteLength,
    };
});

const scripts = files.filter((f) => SCRIPT_EXTENSIONS.has(f.ext)).sort((a, b) => b.gzip - a.gzip);
const fonts = files.filter((f) => FONT_EXTENSIONS.has(f.ext)).sort((a, b) => b.bytes - a.bytes);

const kb = (n) => `${(n / 1024).toFixed(1)} KB`;
const table = (rows, sizeOf, label) => {
    if (rows.length === 0) {
        console.log(`\n${label}: none`);
        return 0;
    }
    const total = rows.reduce((sum, r) => sum + sizeOf(r), 0);
    console.log(`\n${label}`);
    for (const row of rows) console.log(`  ${kb(sizeOf(row)).padStart(10)}  ${row.file}`);
    console.log(`  ${kb(total).padStart(10)}  TOTAL`);
    return total;
};

const scriptTotal = table(scripts, (r) => r.gzip, "JS and CSS (gzip)");
const fontTotal = table(fonts, (r) => r.bytes, "Fonts (already compressed, raw bytes)");

mkdirSync(resolve("reports"), { recursive: true });
writeFileSync(
    OUT,
    `${JSON.stringify({ measuredAt: new Date().toISOString(), scriptTotal, fontTotal, scripts, fonts }, null, 2)}\n`,
);
console.log(`\nWrote ${relative(process.cwd(), OUT)}`);
