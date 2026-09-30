export const OFFICE_TIME_ZONE = process.env.NEXT_PUBLIC_OFFICE_TIME_ZONE?.trim() || "Asia/Kolkata";

export type PublicHost = {
  id: string;
  displayName: string;
  designation: string;
  departmentId: string;
  departmentName: string;
  category: "CEO" | "HR" | "TEAM_LEAD" | "EMPLOYEE";
};

export type AvailableSlot = {
  start: string;
  end: string;
  officeTimeZone: string;
};

export type PublicAppointment = {
  referenceNumber: string;
  type: string;
  status: string;
  hostReference: string;
  slotStart: string;
  slotEnd: string;
  visitorDisplayName: string;
};

function partsForOfficeDate(value: Date) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: OFFICE_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(value);
  return Object.fromEntries(parts.map((part) => [part.type, part.value]));
}

export function officeToday(value = new Date()) {
  const parts = partsForOfficeDate(value);
  return `${parts.year}-${parts.month}-${parts.day}`;
}

export function officeYearMonth(value: string | Date = new Date()) {
  const parts = partsForOfficeDate(typeof value === "string" ? new Date(value) : value);
  return `${parts.year}-${parts.month}`;
}

export function nextBusinessDays(count: number, value = new Date()) {
  const dates: string[] = [];
  const cursor = new Date(`${officeToday(value)}T00:00:00Z`);
  while (dates.length < count) {
    const day = cursor.getUTCDay();
    if (day !== 0 && day !== 6) dates.push(cursor.toISOString().slice(0, 10));
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return dates;
}

export function appointmentDates(count: number, emergency: boolean, value = new Date()) {
  const today = officeToday(value);
  if (!emergency) return nextBusinessDays(count, value);
  return [today, ...nextBusinessDays(count + 1, value).filter((date) => date !== today)].slice(0, count);
}

export function hostCategoryForVisit(visitType: string) {
  if (visitType === "CEO visit") return "CEO" as const;
  if (visitType === "HR visit" || visitType === "Interview") return "HR" as const;
  if (visitType === "Client meeting") return "TEAM_LEAD" as const;
  if (visitType === "Emergency visit") return null;
  if (visitType === "Employee visit" || visitType === "Employee meeting") return "HR" as const;
  return "EMPLOYEE" as const;
}

export function hostCategoriesForVisit(visitType: string): PublicHost["category"][] {
  if (visitType === "Emergency visit") return ["CEO", "HR"];
  const category = hostCategoryForVisit(visitType);
  return category ? [category] : [];
}

export function dateCard(date: string) {
  const value = new Date(`${date}T00:00:00Z`);
  return {
    day: value.toLocaleDateString("en-IN", { weekday: "short", timeZone: "UTC" }).toUpperCase(),
    date: value.toLocaleDateString("en-IN", { day: "2-digit", timeZone: "UTC" }),
    month: value.toLocaleDateString("en-IN", { month: "short", timeZone: "UTC" }),
  };
}

export function officeDateTimeToIso(date: string, time24: string) {
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const timeMatch = /^(\d{2}):(\d{2})$/.exec(time24);
  if (!dateMatch || !timeMatch) throw new Error("Office date and time must use YYYY-MM-DD and HH:mm.");
  const desiredUtc = Date.UTC(Number(dateMatch[1]), Number(dateMatch[2]) - 1, Number(dateMatch[3]),
    Number(timeMatch[1]), Number(timeMatch[2]), 0);
  let candidate = desiredUtc;
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone: OFFICE_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
  });
  // Convert an office-local wall time into UTC without assuming a fixed offset.
  // Repeating resolves zones with daylight-saving transitions as well as fixed zones.
  for (let pass = 0; pass < 3; pass += 1) {
    const parts = Object.fromEntries(formatter.formatToParts(new Date(candidate))
      .map((part) => [part.type, part.value]));
    const representedUtc = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day),
      Number(parts.hour), Number(parts.minute), Number(parts.second));
    const correction = desiredUtc - representedUtc;
    candidate += correction;
    if (correction === 0) break;
  }
  return new Date(candidate).toISOString();
}

export function fallbackSlots(date: string, now = new Date()): AvailableSlot[] {
  const starts = ["09:30", "10:10", "10:50", "11:30", "12:10", "12:50",
    "13:30", "14:10", "14:50", "15:30", "16:10", "16:50"];
  return starts.map((time) => {
    const start = new Date(officeDateTimeToIso(date, time));
    return {
      start: start.toISOString(),
      end: new Date(start.getTime() + 30 * 60 * 1000).toISOString(),
      officeTimeZone: OFFICE_TIME_ZONE,
    };
  }).filter((slot) => new Date(slot.start).getTime() > now.getTime() + 10 * 60 * 1000);
}

export function formatOfficeDate(instant: string, options?: Intl.DateTimeFormatOptions) {
  return new Date(instant).toLocaleDateString("en-IN", {
    timeZone: OFFICE_TIME_ZONE, day: "numeric", month: "long", year: "numeric", ...options,
  });
}

export function formatOfficeTime(instant: string) {
  return new Date(instant).toLocaleTimeString("en-IN", {
    timeZone: OFFICE_TIME_ZONE, hour: "numeric", minute: "2-digit",
  });
}

export function nextReceptionDateTime(value = new Date()) {
  for (const date of nextBusinessDays(8, value)) {
    const slot = fallbackSlots(date, value)[0];
    if (!slot) continue;
    const parts = new Intl.DateTimeFormat("en-GB", {
      timeZone: OFFICE_TIME_ZONE, hour: "2-digit", minute: "2-digit", hour12: false,
    }).formatToParts(new Date(slot.start));
    const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
    return `${date}T${values.hour}:${values.minute}`;
  }
  return `${nextBusinessDays(1, value)[0]}T09:30`;
}

export function appointmentTypeCode(label: string) {
  const values: Record<string, string> = {
    "Employee visit": "EMPLOYEE_VISIT",
    "Employee meeting": "EMPLOYEE_VISIT",
    "HR visit": "HR_VISIT",
    "CEO visit": "CEO_VISIT",
    "Emergency visit": "EMERGENCY",
    Interview: "INTERVIEW",
    "Client meeting": "CLIENT_MEETING",
    "Vendor visit": "VENDOR_VISIT",
  };
  return values[label] ?? "OTHER";
}

export function newDemoReference() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const segment = () => Array.from({ length: 4 }, () => alphabet[crypto.getRandomValues(new Uint32Array(1))[0] % alphabet.length]).join("");
  return `BSA-${segment()}-${segment()}`;
}
