/**
 * Generates (or with `--check` verifies) the typed API schemas for both backends from the
 * checked-in OpenAPI contracts. One script so `pnpm gen:api --check` covers every contract
 * (a `&&` chain would pass the flag to the last command only).
 */
import { spawnSync } from "node:child_process";

const check = process.argv.includes("--check");
const contracts = ["orchestrator", "ledger"];

for (const name of contracts) {
    const args = [
        "exec",
        "openapi-typescript",
        `../docs/api/${name}.openapi.json`,
        "-o",
        `src/lib/api/generated/${name}.ts`,
        ...(check ? ["--check"] : []),
    ];
    const result = spawnSync("pnpm", args, { stdio: "inherit" });
    if (result.status !== 0) {
        console.error(
            `gen:api: ${name} is out of date. Run \`pnpm gen:api\` and commit the result.`,
        );
        process.exit(result.status ?? 1);
    }
}
