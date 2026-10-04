import type { SearchPages, SearchType } from "./types";
export const SEARCH_TYPES: SearchType[] = ["appointments", "visitors", "employees", "worksheets"];
export const INITIAL_SEARCH_PAGES: SearchPages = { appointments: 0, visitors: 0, employees: 0, worksheets: 0 };
export function normalizedSearchQuery(value: string) { return value.trim(); }
export function validSearchQuery(value: string) {
  const q = normalizedSearchQuery(value);
  return q.length >= 2 && q.length <= 100 && !/[\u0000-\u001f\u007f-\u009f]/.test(q);
}
export function searchRequestKey(identityKey: string, query: string, pages: SearchPages) {
  return JSON.stringify([identityKey, normalizedSearchQuery(query), ...SEARCH_TYPES.map(type => pages[type])]);
}
export function searchQueryString(query: string, pages: SearchPages, size = 5) {
  if (!validSearchQuery(query) || !Number.isInteger(size) || size < 1 || size > 25 || SEARCH_TYPES.some(type => !Number.isInteger(pages[type]) || pages[type] < 0 || pages[type] > 999)) throw new Error("Invalid search query or page");
  const params = new URLSearchParams({ q: normalizedSearchQuery(query), size: String(size) });
  SEARCH_TYPES.forEach(type => params.set(`${type}Page`, String(pages[type])));
  return params.toString();
}
/** Identity/query/page changes invalidate both pending queries and record opens synchronously. */
export function createSearchRequestGuard() {
  let generation = 0;
  return { invalidate: () => ++generation, current: () => generation, accepts: (observed: number) => observed === generation };
}
