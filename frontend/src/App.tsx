import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { api, ApiError, setCsrfToken } from "./api";
import { ErrorBanner, Spinner, ThemeSwitch } from "./components";
import { AuditPage, ClientsPage, DevicesPage, OverviewPage, SettingsPage } from "./pages";
import type { Page } from "./pages";
import type { Me, Status } from "./types";
import { errorMessage } from "./lib/format";
import { useTheme } from "./theme";

const PAGE_META: Record<Page, { title: string; subtitle: string }> = {
  overview: { title: "Overview", subtitle: "Access posture and controller health" },
  devices: { title: "Devices", subtitle: "Allowed, blocked, and scheduled devices" },
  clients: { title: "Live clients", subtitle: "What is connected right now" },
  audit: { title: "Audit log", subtitle: "Every action, who did it, and the result" },
  settings: { title: "Settings", subtitle: "Controller capabilities and enforcement" },
};

export default function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [checking, setChecking] = useState(true);
  const [setupRequired, setSetupRequired] = useState(false);
  const [startupError, setStartupError] = useState("");
  const [status, setStatus] = useState<Status | null>(null);
  const [page, setPage] = useState<Page>("overview");
  const [refreshKey, setRefreshKey] = useState(0);
  const [syncing, setSyncing] = useState(false);
  const [toast, setToast] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const { preference, setPreference } = useTheme();

  const loadMe = useCallback(async () => {
    try {
      const current = await api.me();
      setCsrfToken(current.csrf_token);
      setMe(current);
      setStartupError("");
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setMe(null);
      } else {
        setStartupError(errorMessage(err));
      }
    } finally {
      setChecking(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .setupStatus()
      .then((state) => {
        if (cancelled) return;
        if (state.setup_required) {
          setCsrfToken(state.csrf_token);
          setSetupRequired(true);
          setChecking(false);
          return;
        }
        void loadMe();
      })
      .catch((err) => {
        if (!cancelled) {
          setStartupError(errorMessage(err));
          setChecking(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [loadMe]);

  useEffect(() => {
    const onAuthRequired = () => {
      setMe(null);
      setStatus(null);
      setCsrfToken("");
    };
    window.addEventListener("auth:required", onAuthRequired);
    return () => window.removeEventListener("auth:required", onAuthRequired);
  }, []);

  const loadStatus = useCallback(async () => {
    try {
      setStatus(await api.status());
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 401)) {
        setToast({ kind: "error", text: errorMessage(err) });
      }
    }
  }, []);

  useEffect(() => {
    if (!me) return;
    void loadStatus();
    const timer = window.setInterval(() => void loadStatus(), 20_000);
    return () => window.clearInterval(timer);
  }, [me, loadStatus]);

  const onChanged = useCallback(() => {
    setRefreshKey((value) => value + 1);
    void loadStatus();
  }, [loadStatus]);

  async function handleSync() {
    setSyncing(true);
    setToast(null);
    try {
      const summary = await api.syncNow();
      setToast({
        kind: "success",
        text: `Sync complete: ${summary.discovered} discovered, ${summary.applied} applied, ${summary.errors.length} errors.`,
      });
      onChanged();
    } catch (err) {
      setToast({ kind: "error", text: errorMessage(err) });
    } finally {
      setSyncing(false);
    }
  }

  async function handleLogout() {
    try {
      await api.logout();
    } catch {
      // The session may already be gone.
    }
    setCsrfToken("");
    setMe(null);
    setStatus(null);
  }

  if (checking) {
    return (
      <div className="center-screen">
        <Spinner label="Starting" />
      </div>
    );
  }

  if (!me) {
    if (setupRequired) {
      return (
        <>
          <div className="login-theme">
            <ThemeSwitch preference={preference} onChange={setPreference} />
          </div>
          <SetupPage
            startupError={startupError}
            onCompleted={async () => {
              setSetupRequired(false);
              await loadMe();
            }}
          />
        </>
      );
    }
    return (
      <>
        <div className="login-theme">
          <ThemeSwitch preference={preference} onChange={setPreference} />
        </div>
        <LoginPage startupError={startupError} onLoggedIn={loadMe} />
      </>
    );
  }

  const canWrite = me.role === "admin";
  const meta = PAGE_META[page];

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <WifiIcon />
          </div>
          <div>
            <strong>WiFi Access</strong>
            <span>UniFi manager</span>
          </div>
        </div>

        <nav className="nav">
          <NavButton page="overview" current={page} onClick={setPage} label="Overview" glyph="▦" />
          <NavButton page="devices" current={page} onClick={setPage} label="Devices" glyph="◈" />
          <NavButton page="clients" current={page} onClick={setPage} label="Live clients" glyph="◎" />
          <NavButton page="audit" current={page} onClick={setPage} label="Audit log" glyph="≡" />
          <NavButton page="settings" current={page} onClick={setPage} label="Settings" glyph="⚙" />
        </nav>

        <div className="sidebar-footer">
          <div className="muted small">Signed in as</div>
          <strong>{me.display_name || me.username}</strong>
          <div className="muted small">
            {me.role === "admin" ? "Administrator" : "Viewer"} · v{me.version}
          </div>
          <button className="license-link" onClick={() => setPage("settings")}>
            MIT License
          </button>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div>
            <h1>{meta.title}</h1>
            <p className="muted small">{meta.subtitle}</p>
          </div>
          <div className="topbar-actions">
            <ThemeSwitch preference={preference} onChange={setPreference} />
            <span className={`pill ${status?.controller.error ? "pill-bad" : "pill-ok"}`}>
              <span className="pill-dot" />
              {status?.controller.error ? "Controller issue" : status?.controller.site?.name ?? "Controller"}
            </span>
            {canWrite ? (
              <button className="button button-ghost" onClick={handleSync} disabled={syncing}>
                {syncing ? "Syncing…" : "Sync now"}
              </button>
            ) : null}
            <button className="button button-ghost" onClick={handleLogout}>
              Sign out
            </button>
          </div>
        </header>

        {toast ? (
          <div className={`banner ${toast.kind === "success" ? "banner-success" : "banner-error"}`}>
            <span>{toast.text}</span>
            <button className="icon-button" onClick={() => setToast(null)} aria-label="Dismiss">
              ×
            </button>
          </div>
        ) : null}

        {page === "overview" ? (
          <OverviewPage
            status={status}
            refreshKey={refreshKey}
            onNavigate={setPage}
            onSync={handleSync}
            syncing={syncing}
            canWrite={canWrite}
          />
        ) : null}
        {page === "devices" ? (
          <DevicesPage
            status={status}
            refreshKey={refreshKey}
            onChanged={onChanged}
            canWrite={canWrite}
          />
        ) : null}
        {page === "clients" ? (
          <ClientsPage
            status={status}
            refreshKey={refreshKey}
            onChanged={onChanged}
            canWrite={canWrite}
          />
        ) : null}
        {page === "audit" ? <AuditPage refreshKey={refreshKey} /> : null}
        {page === "settings" ? (
          <SettingsPage
            status={status}
            me={me}
            canWrite={canWrite}
            onSync={handleSync}
            syncing={syncing}
            onRefreshStatus={() => void loadStatus()}
          />
        ) : null}
      </main>
    </div>
  );
}

function NavButton({
  page,
  current,
  onClick,
  label,
  glyph,
}: {
  page: Page;
  current: Page;
  onClick: (page: Page) => void;
  label: string;
  glyph: string;
}) {
  return (
    <button
      className={page === current ? "nav-item active" : "nav-item"}
      onClick={() => onClick(page)}
    >
      <span className="nav-glyph" aria-hidden="true">
        {glyph}
      </span>
      {label}
    </button>
  );
}

function LoginPage({
  startupError,
  onLoggedIn,
}: {
  startupError: string;
  onLoggedIn: () => Promise<void>;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const result = await api.login(username.trim(), password);
      setCsrfToken(result.csrf_token);
      await onLoggedIn();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <div className="brand-mark large">
            <WifiIcon />
          </div>
          <h1>UniFi WiFi Access</h1>
          <p className="muted small">Sign in to manage device access</p>
        </div>
        {startupError ? <ErrorBanner message={startupError} /> : null}
        {error ? <ErrorBanner message={error} onDismiss={() => setError("")} /> : null}
        <label>
          <span>Username</span>
          <input
            autoFocus
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
        </label>
        <label>
          <span>Password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        <button className="button button-primary button-wide" type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        <p className="login-license muted small">
          Released under the{" "}
          <a href="/LICENSE" target="_blank" rel="noreferrer">
            MIT License
          </a>
          .
        </p>
      </form>
    </div>
  );
}

function SetupPage({
  startupError,
  onCompleted,
}: {
  startupError: string;
  onCompleted: () => Promise<void>;
}) {
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (password !== confirm) {
      setError("Passwords do not match.");
      return;
    }
    setBusy(true);
    try {
      const result = await api.completeSetup({
        username: username.trim(),
        display_name: displayName.trim(),
        password,
      });
      setCsrfToken(result.csrf_token);
      await onCompleted();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <div className="brand-mark large">
            <WifiIcon />
          </div>
          <h1>Create the first admin</h1>
          <p className="muted small">
            This setup screen appears only once, while no users exist.
          </p>
        </div>
        {startupError ? <ErrorBanner message={startupError} /> : null}
        {error ? <ErrorBanner message={error} onDismiss={() => setError("")} /> : null}
        <label>
          <span>Username</span>
          <input
            autoFocus
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            placeholder="admin"
          />
        </label>
        <label>
          <span>Display name (optional)</span>
          <input
            autoComplete="name"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
        </label>
        <label>
          <span>Password</span>
          <input
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <span className="muted small">At least 12 characters; a passphrase works well.</span>
        </label>
        <label>
          <span>Confirm password</span>
          <input
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
          />
        </label>
        <button className="button button-primary button-wide" type="submit" disabled={busy}>
          {busy ? "Creating admin…" : "Create admin"}
        </button>
        <p className="login-license muted small">
          Credentials are stored in the local SQLite database as salted scrypt hashes.
        </p>
      </form>
    </div>
  );
}

function WifiIcon() {  return (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M3.5 8.5a13 13 0 0 1 17 0" strokeLinecap="round" />
      <path d="M6.5 12a8.5 8.5 0 0 1 11 0" strokeLinecap="round" />
      <path d="M9.5 15.5a4 4 0 0 1 5 0" strokeLinecap="round" />
      <circle cx="12" cy="19" r="1.2" fill="currentColor" stroke="none" />
    </svg>
  );
}
