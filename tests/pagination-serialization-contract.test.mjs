import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

const paginationConfiguration = fs.readFileSync(
    "backend/src/main/java/com/brainserve/appointment/shared/config/PaginationConfiguration.java",
    "utf8",
);

const api = fs.readFileSync("app/lib/api.ts", "utf8");

test("Spring pages use the supported stable DTO serialization mode", () => {
    assert.match(
        paginationConfiguration,
        /@EnableSpringDataWebSupport\(pageSerializationMode = VIA_DTO\)/,
    );

    assert.match(
        paginationConfiguration,
        /EnableSpringDataWebSupport\.PageSerializationMode\.VIA_DTO/,
    );
});

test("frontend normalizes stable nested page metadata without breaking legacy responses", () => {
    assert.match(
        api,
        /const number = result\.number \?\? result\.page\?\.number/,
    );

    assert.match(
        api,
        /const totalElements = result\.totalElements \?\? result\.page\?\.totalElements/,
    );

    assert.match(
        api,
        /const totalPages = result\.totalPages \?\? result\.page\?\.totalPages/,
    );

    assert.match(
        api,
        /employees: normalizeSpringPage\(workspace\.employees\)/,
    );
});

test("all direct Spring page requests pass through the compatibility normalizer", () => {
    const directRequests = api.match(/apiRequest<SpringPage/g) ?? [];

    assert.equal(directRequests.length, 1);

    assert.match(
        api,
        /return normalizeSpringPage\(await apiRequest<SpringPage<T>>/,
    );
});