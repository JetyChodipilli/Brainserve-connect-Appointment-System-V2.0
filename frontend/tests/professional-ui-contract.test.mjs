import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("the workspace uses self-hosted professional typography and one design layer", () => {
    const layout = read("app/layout.tsx");
    const styles = read("app/professional-ui.css");
    const packageJson = JSON.parse(read("package.json"));

    assert.match(layout, /@fontsource-variable\/manrope\/wght\.css/);
    assert.match(layout, /@fontsource-variable\/newsreader\/wght\.css/);
    assert.match(layout, /\.\/professional-ui\.css/);
    assert.equal(packageJson.dependencies["@fontsource-variable/manrope"], "5.3.0");
    assert.equal(packageJson.dependencies["@fontsource-variable/newsreader"], "5.3.0");
    assert.match(styles, /--font-ui: "Manrope Variable"/);
    assert.match(styles, /--font-display: "Newsreader Variable"/);
});

test("responsive cards and data views stay contained without changing workflows", () => {
    const app = readFrontendSource();
    const styles = read("app/professional-ui.css");

    assert.match(styles, /\.task-sheet-grid \{[\s\S]*minmax\(min\(100%, 350px\), 1fr\)/);
    assert.match(styles, /\.task-sheet-summary-main p,[\s\S]*overflow-wrap: anywhere/);
    assert.match(styles, /\.records-table-wrap \{[\s\S]*overflow-x: auto/);
    assert.match(styles, /@media \(max-width: 800px\)[\s\S]*\.sidebar-scrim/);
    assert.match(styles, /@media \(max-width: 640px\)[\s\S]*\.task-sheet-summary-meta/);
    assert.match(app, /className="sidebar-scrim"/);
    assert.match(app, /id="workspace-main"/);
    assert.match(app, /aria-current=\{view === item\.id \? "page" : undefined\}/);
});

test("motion is restrained, accessible and compositor friendly", () => {
    const styles = read("app/professional-ui.css");

    assert.match(styles, /--ease-spring: cubic-bezier/);
    assert.match(styles, /@keyframes ui-view-enter[\s\S]*transform: translateY/);
    assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/);
    assert.match(styles, /@media \(forced-colors: active\)/);
    assert.doesNotMatch(styles, /transition:[^;]*(?:\btop\b|\bleft\b|\bwidth\b|\bheight\b)/);
});

