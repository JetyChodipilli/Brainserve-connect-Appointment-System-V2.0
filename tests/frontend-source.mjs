import { readFileSync, readdirSync } from "node:fs";

const root = new URL("../app/", import.meta.url);
const directories = ["application", "features", "preview", "shared", "workspace"];

function readDirectory(directory) {
    return readdirSync(directory, { withFileTypes: true })
        .sort((left, right) => left.name.localeCompare(right.name))
        .flatMap((entry) => {
            const location = new URL(entry.name + (entry.isDirectory() ? "/" : ""), directory);
            if (entry.isDirectory()) return readDirectory(location);
            return /\.tsx?$/.test(entry.name) ? [readFileSync(location, "utf8")] : [];
        });
}

/** Read the actual extracted frontend so existing contracts survive file moves. */
export function readFrontendSource() {
    return [readFileSync(new URL("brainserve-app.tsx", root), "utf8"),
        ...directories.flatMap((directory) => readDirectory(new URL(directory + "/", root)))]
        .join("\n\n");
}

/** Inspect a single feature when a contract is specific to that feature. */
export function readFrontendModule(modulePath) {
    return readFileSync(new URL(modulePath, root), "utf8");
}
