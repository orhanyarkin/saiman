/**
 * Lighthouse accessibility gate (M5, docs/design/m5-dashboard.md). Serves the production build with
 * `vite preview` against the fixture server (the same stand-in the e2e tests use), visits every
 * route of the dashboard with the accessibility category only, prints one score per route and
 * exits non-zero when any score is below the threshold. HTML and JSON reports go to the
 * gitignored `reports/lighthouse/`.
 *
 * Run through `pnpm lighthouse` (builds first). Chromium comes from Playwright's install
 * (`pnpm exec playwright install chromium`), or from `CHROME_PATH` when set.
 */
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import { chromium } from "@playwright/test";
import { launch } from "chrome-launcher";
import lighthouse, { desktopConfig } from "lighthouse";

const THRESHOLD = 0.9;
const FIXTURE_PORT = Number(process.env.SAIMAN_LH_FIXTURE_PORT ?? 4120);
const APP_PORT = Number(process.env.SAIMAN_LH_APP_PORT ?? 4121);
const REPLAY_PORT = Number(process.env.SAIMAN_LH_REPLAY_PORT ?? 4122);
const APP = `http://localhost:${String(APP_PORT)}`;
const REPLAY_APP = `http://localhost:${String(REPLAY_PORT)}`;
const OUT = resolve("reports/lighthouse");

// Ids of the example capture (e2e/fixtures/*.json): a finished run, a payment, a reconciliation run.
const RUN = "6ad4354c-8e79-4b49-b5d9-d45eb9689b41";
const PAYMENT = "3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e52";
const RECON = "9b1f0c52-3a7e-4d68-8f21-5c0d1e2f3a61";

const ROUTES = [
    "/",
    "/runs/new",
    `/runs/${RUN}`,
    "/runs",
    "/approvals",
    "/spend",
    "/ledger",
    `/ledger/payments/${PAYMENT}`,
    "/reconciliation",
    `/reconciliation/${RECON}`,
    "/revenue",
    "/connect", // opens the Connect dialog (ADR-0023): the dialog itself is what gets audited
];

const children = [];

function start(command, args, env) {
    const child = spawn(command, args, {
        env: { ...process.env, ...env },
        stdio: ["ignore", "ignore", "inherit"],
    });
    children.push(child);
    return child;
}

async function waitFor(url, label) {
    for (let i = 0; i < 100; i++) {
        try {
            if ((await fetch(url)).ok) {
                return;
            }
        } catch {
            // not up yet
        }
        await new Promise((done) => setTimeout(done, 200));
    }
    throw new Error(`${label} did not come up at ${url}`);
}

const slug = (route, prefix) =>
    `${prefix}${route === "/" ? "landing" : route.slice(1).replaceAll("/", "_")}`;

async function main() {
    rmSync(OUT, { recursive: true, force: true });
    mkdirSync(OUT, { recursive: true });

    // The replay build (ADR-0026): VITE_DEMO_MODE=replay into its own dir, served as plain files.
    const built = spawnSync(
        "pnpm",
        ["exec", "vite", "build", "--outDir", "dist-replay", "--emptyOutDir", "--logLevel", "warn"],
        {
            env: { ...process.env, VITE_DEMO_MODE: "replay", VITE_OTEL_ENABLED: "false" },
            stdio: "inherit",
        },
    );
    if (built.status !== 0) {
        throw new Error("replay build failed");
    }

    // Auth off for the live build (a cold, protected API would only show the Connect dialog on
    // every page); the dialog itself is audited through /connect.
    start("node", ["e2e/fixture-server.ts"], {
        SAIMAN_FIXTURE_PORT: String(FIXTURE_PORT),
        SAIMAN_FIXTURE_AUTH: "off",
    });
    start("pnpm", ["exec", "vite", "preview", "--port", String(APP_PORT), "--strictPort"], {
        SAIMAN_API_TARGET: `http://127.0.0.1:${String(FIXTURE_PORT)}`,
    });
    start(
        "pnpm",
        [
            "exec",
            "vite",
            "preview",
            "--outDir",
            "dist-replay",
            "--port",
            String(REPLAY_PORT),
            "--strictPort",
        ],
        {},
    );
    await waitFor(`http://127.0.0.1:${String(FIXTURE_PORT)}/api/v1/ping`, "fixture server");
    await waitFor(APP, "vite preview");
    await waitFor(REPLAY_APP, "vite preview (replay)");

    // An explicit profile dir: under WSL chrome-launcher would otherwise create a Windows-style
    // path inside the current directory.
    const userDataDir = mkdtempSync(join(tmpdir(), "saiman-lighthouse-"));
    const chrome = await launch({
        userDataDir: false, // chrome-launcher mangles the path under WSL; pass the flag ourselves
        chromePath: process.env.CHROME_PATH ?? chromium.executablePath(),
        chromeFlags: [
            `--user-data-dir=${userDataDir}`,
            "--headless=new",
            "--no-sandbox",
            "--disable-gpu",
            "--disable-dev-shm-usage",
        ],
    });

    let failed = false;
    try {
        for (const { base, prefix, label } of [
            { base: APP, prefix: "", label: "live" },
            { base: REPLAY_APP, prefix: "replay_", label: "replay" },
        ])
            for (const route of ROUTES) {
                const result = await lighthouse(
                    `${base}${route}`,
                    {
                        port: chrome.port,
                        onlyCategories: ["accessibility"],
                        output: ["html", "json"],
                        logLevel: "error",
                        maxWaitForLoad: 10_000,
                        throttlingMethod: "provided",
                    },
                    desktopConfig,
                );
                if (!result) {
                    throw new Error(`no result for ${route}`);
                }
                const [html, json] = result.report;
                writeFileSync(resolve(OUT, `${slug(route, prefix)}.html`), html);
                writeFileSync(resolve(OUT, `${slug(route, prefix)}.json`), json);

                const score = result.lhr.categories.accessibility?.score ?? 0;
                const ok = score >= THRESHOLD;
                failed ||= !ok;
                console.log(`${ok ? "PASS" : "FAIL"} ${score.toFixed(2)}  [${label}] ${route}`);
                for (const audit of Object.values(result.lhr.audits)) {
                    if (
                        audit.score !== null &&
                        audit.score < 1 &&
                        audit.scoreDisplayMode === "binary"
                    ) {
                        console.log(`       - ${audit.id}: ${audit.title}`);
                    }
                }
            }
    } finally {
        await chrome.kill();
        rmSync(userDataDir, { recursive: true, force: true });
    }

    console.log(`reports: ${OUT}`);
    if (failed) {
        console.error(`Lighthouse accessibility below ${String(THRESHOLD)} on at least one route.`);
        process.exitCode = 1;
    }
}

try {
    await main();
} catch (error) {
    console.error(error);
    process.exitCode = 1;
} finally {
    for (const child of children) {
        child.kill("SIGTERM");
    }
}
