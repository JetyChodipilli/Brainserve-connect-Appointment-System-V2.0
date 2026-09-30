import { readFileSync, readdirSync } from "node:fs";

const root = new URL("../", import.meta.url);
const directories = ["features", "components", "hooks", "preview", "config", "types", "utils"];

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
    return directories.flatMap((directory) => readDirectory(new URL(directory + "/", root)))
        .join("\n\n");
}

/** Read the shared transport and real feature endpoints after API extraction. */
export function readApiSource() {
    const features = new URL("features/", root);
    return [readFileSync(new URL("lib/api-client.ts", root), "utf8"),
        ...readDirectory(new URL("types/", root)),
        ...readDirectory(new URL("features/workboard/types/", root)),
        ...readdirSync(features, { withFileTypes: true })
            .filter((entry) => entry.isDirectory())
            .flatMap((entry) => {
                const directory = new URL(entry.name + "/", features);
                return readdirSync(directory).includes("api")
                    ? readDirectory(new URL("api/", directory)) : [];
            })].join("\n\n");
}

/** Inspect a single feature when a contract is specific to that feature. */
export function readFrontendModule(modulePath) {
    return readFileSync(new URL(modulePath, root), "utf8");
}
