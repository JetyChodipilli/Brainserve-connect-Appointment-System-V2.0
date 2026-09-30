import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import ts from "typescript";

const appRoot = fileURLToPath(new URL("../app/", import.meta.url));
const appPath = (file) => relative(appRoot, file).replaceAll("\\", "/");
function sourceFiles(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const file = resolve(directory, entry.name);
        return entry.isDirectory() ? sourceFiles(file) : /\.tsx?$/.test(file) ? [file] : [];
    });
}
const files = sourceFiles(appRoot);
const fileSet = new Set(files);
const nodes = new Map(files.map((file) => [file,
    ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true)]));

function dependency(file, specifier) {
    const base = resolve(dirname(file), specifier);
    return [base, base + ".ts", base + ".tsx", resolve(base, "index.ts"), resolve(base, "index.tsx")]
        .find((candidate) => fileSet.has(candidate));
}

test("the application entry delegates to the root and keeps feature implementations separate", () => {
    const entry = readFileSync(resolve(appRoot, "brainserve-app.tsx"), "utf8");
    assert.ok(entry.split("\n").length < 50);
    assert.match(entry, /BackendBrainServeApp/);
    for (const feature of ["appointments", "accounts", "employees", "organization", "visitors", "work", "reports", "settings"]) {
        assert.ok(files.some((file) => appPath(file).startsWith(`features/${feature}/`)));
    }
});

test("extracted frontend imports resolve and have no runtime dependency cycles", () => {
    const graph = new Map();
    for (const [file, source] of nodes) {
        const dependencies = [];
        for (const statement of source.statements) {
            if (!ts.isImportDeclaration(statement)) continue;
            const specifier = statement.moduleSpecifier.text;
            if (!specifier.startsWith(".") || /\.(css|json)$/.test(specifier)) continue;
            const target = dependency(file, specifier);
            assert.ok(target, `${relative(appRoot, file)}: unresolved ${specifier}`);
            const clause = statement.importClause;
            const named = clause?.namedBindings;
            const typeOnly = clause?.isTypeOnly || (!clause?.name && named && ts.isNamedImports(named)
                && named.elements.every((item) => item.isTypeOnly));
            if (!typeOnly) dependencies.push(target);
        }
        graph.set(file, dependencies);
    }
    const active = new Set(), completed = new Set();
    function visit(file) {
        assert.ok(!active.has(file), `Runtime cycle at ${relative(appRoot, file)}`);
        if (completed.has(file)) return;
        active.add(file);
        for (const target of graph.get(file)) visit(target);
        active.delete(file);
        completed.add(file);
    }
    for (const file of files) visit(file);
});

test("features use explicit dependencies instead of importing the application entry or layout", () => {
    for (const [file, source] of nodes) {
        if (!appPath(file).startsWith("features/")) continue;
        for (const statement of source.statements) {
            if (!ts.isImportDeclaration(statement)) continue;
            const specifier = statement.moduleSpecifier.text;
            assert.doesNotMatch(specifier, /(?:brainserve-app|brainserve-root|dashboard-app)$/);
            if (specifier.includes("workspace/")) {
                assert.ok(statement.importClause?.isTypeOnly
                    || (ts.isNamedImports(statement.importClause?.namedBindings)
                        && statement.importClause.namedBindings.elements.every((item) => item.isTypeOnly)),
                `${relative(appRoot, file)} must only use workspace types`);
            }
        }
    }
});
