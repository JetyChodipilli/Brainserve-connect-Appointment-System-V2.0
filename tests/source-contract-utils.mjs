const normalizeSource = (value) => value
    .replace(/\s+/g, "")
    .replace(/,([\)\]\}])/g, "$1");

/**
 * Source contract tests should verify behavior-bearing tokens, not formatter output.
 * Java and TypeScript formatters are free to wrap argument lists and apply
 * trailing-comma conventions.
 */
export const sourceIncludes = (source, expected) =>
    normalizeSource(source).includes(normalizeSource(expected));
