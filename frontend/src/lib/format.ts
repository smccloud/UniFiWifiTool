import type { Schedule } from "../types";

export const DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function relativeTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  const diffMs = date.getTime() - Date.now();
  const abs = Math.abs(diffMs);
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  if (abs < 60_000) return formatter.format(Math.round(diffMs / 1000), "second");
  if (abs < 3_600_000) return formatter.format(Math.round(diffMs / 60_000), "minute");
  if (abs < 86_400_000) return formatter.format(Math.round(diffMs / 3_600_000), "hour");
  return formatter.format(Math.round(diffMs / 86_400_000), "day");
}

export function formatDays(days: number[]): string {
  const sorted = [...days].sort((a, b) => a - b);
  if (sorted.length === 7) return "Every day";
  if (sorted.join(",") === "0,1,2,3,4") return "Weekdays";
  if (sorted.join(",") === "5,6") return "Weekends";
  return sorted.map((day) => DAY_NAMES[day]).join(", ");
}

export function describeSchedule(schedule: Schedule): string {
  const fallback =
    schedule.default_access === "blocked"
      ? "Blocked outside windows"
      : "Allowed outside windows";
  const windows = schedule.windows
    .map(
      (window) =>
        `${window.action === "allow" ? "Allow" : "Block"} ${formatDays(window.days)} ${window.start}–${window.end}`,
    )
    .join(" · ");
  return `${fallback}. ${windows} (${schedule.timezone})`;
}

export function signalLabel(signal: number | null): string {
  if (signal === null) return "—";
  return `${signal} dBm`;
}

export function formatSpeed(kbps: number): string {
  if (kbps >= 1000) {
    const mbps = kbps / 1000;
    return `${Number(mbps.toFixed(mbps % 1 === 0 ? 0 : 1))} Mbps`;
  }
  return `${Math.round(kbps)} Kbps`;
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}
