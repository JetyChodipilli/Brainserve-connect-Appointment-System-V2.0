export type SearchType = "appointments" | "visitors" | "employees" | "worksheets";
export type SearchPages = Record<SearchType, number>;
export type SearchItem = { id: string; title: string; subtitle: string; status: string };
export type SearchGroup = {
  type: SearchType; label: string; available: boolean; coverage: string;
  number: number; size: number; totalElements: number; totalPages: number; items: SearchItem[];
};
export type SearchResponse = { query: string; groups: SearchGroup[] };
export type SearchOpenRecord = SearchItem & {
  type: SearchType; route: "appointments" | "visitors" | "employees" | "work" | "insights";
  detail: Record<string, string>;
};
