import type {
  AuditList,
  AuditPurgeResult,
  ClientList,
  Device,
  DeviceList,
  EditableAccessMode,
  LicenseInfo,
  Me,
  Schedule,
  Status,
  SyncSummary,
  WifiNetwork,
} from "./types";

let csrfToken = "";

export function setCsrfToken(token: string): void {
  csrfToken = token;
}

export class ApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (!["GET", "HEAD", "OPTIONS"].includes(method) && csrfToken) {
    headers.set("X-CSRF-Token", csrfToken);
  }

  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 15_000);

  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers,
      credentials: "same-origin",
      signal: init.signal ?? controller.signal,
    });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      throw new ApiError("Request timed out", 0);
    }
    throw new ApiError("Cannot reach the server", 0);
  } finally {
    window.clearTimeout(timeout);
  }

  if (response.status === 401) {
    window.dispatchEvent(new Event("auth:required"));
    throw new ApiError("Authentication required", 401);
  }

  if (!response.ok) {
    let detail = `Request failed (${response.status})`;
    try {
      const body = (await response.json()) as { detail?: unknown };
      if (typeof body.detail === "string") {
        detail = body.detail;
      } else if (Array.isArray(body.detail)) {
        detail = body.detail
          .map((item) => (typeof item === "object" && item && "msg" in item ? String(item.msg) : String(item)))
          .join("; ");
      }
    } catch {
      // Keep the status-based message.
    }
    throw new ApiError(detail, response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") {
      search.set(key, String(value));
    }
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : "";
}

export const api = {
  login: (username: string, password: string) =>
    request<{ username: string; csrf_token: string }>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),

  logout: () => request<{ ok: boolean }>("/api/auth/logout", { method: "POST" }),

  me: () => request<Me>("/api/auth/me"),

  status: () => request<Status>("/api/system/status"),

  syncNow: () => request<SyncSummary>("/api/system/sync", { method: "POST" }),

  testConnection: () => request<Status["controller"]>("/api/system/test", { method: "POST" }),

  devices: (params: { q?: string; access_mode?: string; limit?: number; offset?: number } = {}) =>
    request<DeviceList>(`/api/devices${query(params)}`),

  device: (id: number) => request<Device>(`/api/devices/${id}`),

  createDevice: (payload: {
    mac: string;
    label?: string;
    notes?: string;
    access_mode?: EditableAccessMode;
  }) =>
    request<Device>("/api/devices", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  updateDevice: (
    id: number,
    payload: {
      label?: string;
      notes?: string;
      access_mode?: EditableAccessMode;
      apply?: boolean;
    },
  ) =>
    request<Device>(`/api/devices/${id}`, {
      method: "PATCH",
      body: JSON.stringify(payload),
    }),

  deleteDevice: (id: number) =>
    request<{ ok: boolean; mac: string }>(`/api/devices/${id}`, { method: "DELETE" }),

  setSchedule: (id: number, schedule: Schedule) =>
    request<Device>(`/api/devices/${id}/schedule`, {
      method: "POST",
      body: JSON.stringify({ schedule, apply: true }),
    }),

  removeSchedule: (id: number) =>
    request<Device>(`/api/devices/${id}/schedule`, { method: "DELETE" }),

  applyDevice: (id: number) =>
    request<Device>(`/api/devices/${id}/apply`, { method: "POST" }),

  startDiscipline: (
    id: number,
    payload: { download_kbps: number; upload_kbps: number; duration_minutes?: number },
  ) =>
    request<Device>(`/api/devices/${id}/discipline`, {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  endDiscipline: (id: number) =>
    request<Device>(`/api/devices/${id}/discipline`, { method: "DELETE" }),

  clients: (includeWired = false) =>
    request<ClientList>(`/api/clients${query({ include_wired: includeWired })}`),

  audit: (params: { mac?: string; action?: string; result?: string; limit?: number; offset?: number } = {}) =>
    request<AuditList>(`/api/audit${query(params)}`),

  wifi: () => request<WifiNetwork[]>("/api/wifi"),

  license: () => request<LicenseInfo>("/api/license"),

  setAuditRetention: (days: number) =>
    request<AuditPurgeResult>("/api/system/audit-retention", {
      method: "PUT",
      body: JSON.stringify({ days }),
    }),

  purgeAudit: () =>
    request<AuditPurgeResult>("/api/system/audit-purge", { method: "POST" }),
};
