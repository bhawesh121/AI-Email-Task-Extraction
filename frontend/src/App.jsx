// import React, { useState } from "react";
// import { Routes, Route } from "react-router-dom";
// import Sidebar from "./components/Sidebar";
// import DashboardPage from "./pages/DashboardPage";
// import TasksPage from "./pages/TasksPage";
// import SettingsPage from "./pages/SettingsPage";
// import { MailIcon } from "./components/Icons";
// import { loginUrl } from "./lib/api";
// import { AppDataProvider, useAppData } from "./lib/AppDataContext";

// function MicrosoftLogo() {
//   return (
//     <svg width="20" height="20" viewBox="0 0 21 21" aria-hidden="true">
//       <rect x="1" y="1" width="9" height="9" fill="#f25022" />
//       <rect x="11" y="1" width="9" height="9" fill="#7fba00" />
//       <rect x="1" y="11" width="9" height="9" fill="#00a4ef" />
//       <rect x="11" y="11" width="9" height="9" fill="#ffb900" />
//     </svg>
//   );
// }

// function LoginGate() {
//   // Real interaction feedback: the anchor still does a full page navigation
//   // (required — this has to leave the SPA and hit the backend's redirect
//   // chain to Microsoft), but the button now reflects that a redirect is in
//   // flight instead of just sitting there looking clickable forever.
//   const [redirecting, setRedirecting] = useState(false);

//   return (
//     <div className="flex min-h-screen items-center justify-center bg-[#f7f7fb] px-4">
//       <div className="grid w-full max-w-3xl overflow-hidden rounded-2xl border border-ink-900/5 bg-white shadow-card md:grid-cols-2">
//         <div className="relative hidden flex-col justify-between bg-brand-500 p-8 text-white md:flex">
//           <div className="flex items-center gap-2.5">
//             <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-white/15">
//               <MailIcon color="white" />
//             </div>
//             <span className="text-[15px] font-semibold">AI Email Assistant</span>
//           </div>

//           <div>
//             <p className="text-xl font-semibold leading-snug">
//               Every inbox turned into a task list, automatically.
//             </p>
//             <p className="mt-2 text-sm text-white/70">
//               Emails are triaged, deduplicated, and assigned in real time — this
//               dashboard just shows you what's already happened.
//             </p>
//           </div>

//           <ul className="space-y-2 text-sm text-white/80">
//             <li className="flex items-center gap-2">
//               <span className="h-1.5 w-1.5 rounded-full bg-white/60" />
//               Tasks generated from real inbox activity
//             </li>
//             <li className="flex items-center gap-2">
//               <span className="h-1.5 w-1.5 rounded-full bg-white/60" />
//               Deadlines and priority tracked automatically
//             </li>
//           </ul>
//         </div>

//         <div className="flex flex-col justify-center p-8 sm:p-10">
//           <div className="mb-6 flex h-11 w-11 items-center justify-center rounded-xl bg-brand-50 md:hidden">
//             <MailIcon />
//           </div>

//           <h1 className="text-xl font-bold text-ink-900">Sign in to continue</h1>
//           <p className="mt-2 text-sm leading-relaxed text-ink-500">
//             This dashboard reads live data from the aiassistant API, which is
//             secured with your organization's Microsoft account.
//           </p>

//           <a
//             href={loginUrl}
//             onClick={() => setRedirecting(true)}
//             aria-disabled={redirecting}
//             className={`mt-6 flex w-full items-center justify-center gap-3 rounded-lg border border-ink-900/10 px-4 py-3 text-sm font-semibold shadow-sm transition-colors ${
//               redirecting
//                 ? "cursor-wait bg-ink-900/5 text-ink-300"
//                 : "bg-white text-ink-900 hover:bg-ink-900/[0.03]"
//             }`}
//           >
//             {redirecting ? (
//               <>
//                 <span className="h-4 w-4 animate-spin rounded-full border-2 border-ink-300 border-t-transparent" />
//                 Redirecting to Microsoft…
//               </>
//             ) : (
//               <>
//                 <MicrosoftLogo />
//                 Continue with Microsoft
//               </>
//             )}
//           </a>

//           <p className="mt-4 text-xs text-ink-300">
//             You'll be redirected to your organization's Microsoft sign-in page,
//             then back here automatically.
//           </p>
//         </div>
//       </div>
//     </div>
//   );
// }

// function AppShell() {
//   const { unauthenticated } = useAppData();

//   if (unauthenticated) return <LoginGate />;

//   return (
//     <div className="flex h-screen overflow-hidden bg-[#f7f7fb]">
//       <Sidebar />
//       <main className="flex-1 overflow-y-auto">
//         <Routes>
//           <Route path="/" element={<DashboardPage />} />
//           <Route path="/tasks" element={<TasksPage />} />
//           <Route path="/settings" element={<SettingsPage />} />
//         </Routes>
//       </main>
//     </div>
//   );
// }

// export default function App() {
//   return (
//     <AppDataProvider>
//       <AppShell />
//     </AppDataProvider>
//   );
// }

import React, { useEffect, useState } from "react";
import { Routes, Route } from "react-router-dom";
import Sidebar from "./components/Sidebar";
import DashboardPage from "./pages/DashboardPage";
import TasksPage from "./pages/TasksPage";
import SettingsPage from "./pages/SettingsPage";
import { MailIcon } from "./components/Icons";
import { loginUrl } from "./lib/api";
import { AppDataProvider, useAppData } from "./lib/AppDataContext";

function MicrosoftLogo() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 21 21"
      aria-hidden="true"
    >
      <rect x="1" y="1" width="9" height="9" fill="#f25022" />
      <rect x="11" y="1" width="9" height="9" fill="#7fba00" />
      <rect x="1" y="11" width="9" height="9" fill="#00a4ef" />
      <rect x="11" y="11" width="9" height="9" fill="#ffb900" />
    </svg>
  );
}

function LoginGate() {
  const [redirecting, setRedirecting] = useState(false);

  // Spring Security's oauth2Login() failureUrl sends the browser back to
  // ".../login?error" on a real navigation (not client-side routing) when
  // the Microsoft sign-in attempt itself fails. AppShell renders this
  // component regardless of path whenever unauthenticated, so this just
  // reads the query string once on mount to decide whether to show a
  // banner - it does not gate which component renders.
  const [showAuthError] = useState(
    () => new URLSearchParams(window.location.search).has("error")
  );

  useEffect(() => {
    if (showAuthError && window.history?.replaceState) {
      // Strip "?error" so a refresh doesn't keep re-showing the banner.
      window.history.replaceState(null, "", window.location.pathname);
    }
  }, [showAuthError]);

  return (
    <div className="min-h-screen bg-[#f7f7fb]">
      <div className="mx-auto flex min-h-screen w-full max-w-6xl items-center justify-center px-5 py-10 sm:px-8">
        <div className="grid w-full overflow-hidden rounded-2xl border border-ink-900/10 bg-white shadow-card lg:grid-cols-[1.05fr_0.95fr]">
          {/* Brand / product side */}
          <div className="relative hidden min-h-[560px] overflow-hidden bg-brand-600 p-10 text-white lg:flex lg:flex-col">
            <div className="absolute right-[-90px] top-[-90px] h-64 w-64 rounded-full border border-white/10" />
            <div className="absolute bottom-[-120px] left-[-80px] h-72 w-72 rounded-full border border-white/10" />

            {/* Brand */}
            <div className="relative flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-white/10 ring-1 ring-white/10">
                <MailIcon color="white" />
              </div>

              <div>
                <p className="text-[15px] font-semibold tracking-tight">
                  AI Email Assistant
                </p>

                <p className="mt-0.5 text-[11px] text-white/60">
                  Business email intelligence
                </p>
              </div>
            </div>

            {/* Main message */}
            <div className="relative mt-auto">
              <p className="max-w-md text-[28px] font-semibold leading-[1.2] tracking-[-0.02em]">
                Turn incoming email into organized work.
              </p>

              <p className="mt-4 max-w-md text-sm leading-6 text-white/70">
                Emails are triaged, deduplicated, and converted into
                actionable tasks so your team can focus on what needs
                attention.
              </p>

              {/* Product capabilities */}
              <div className="mt-8 space-y-4">
                <div className="flex items-start gap-3">
                  <div className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-white/10 text-[10px]">
                    ✓
                  </div>

                  <div>
                    <p className="text-sm font-medium text-white">
                      Task generation
                    </p>

                    <p className="mt-0.5 text-xs leading-5 text-white/55">
                      Actionable work is identified from incoming email.
                    </p>
                  </div>
                </div>

                <div className="flex items-start gap-3">
                  <div className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-white/10 text-[10px]">
                    ✓
                  </div>

                  <div>
                    <p className="text-sm font-medium text-white">
                      Priority and deadlines
                    </p>

                    <p className="mt-0.5 text-xs leading-5 text-white/55">
                      Important work stays visible and organized.
                    </p>
                  </div>
                </div>

                <div className="flex items-start gap-3">
                  <div className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-white/10 text-[10px]">
                    ✓
                  </div>

                  <div>
                    <p className="text-sm font-medium text-white">
                      Centralized workload
                    </p>

                    <p className="mt-0.5 text-xs leading-5 text-white/55">
                      Keep task ownership and activity in one place.
                    </p>
                  </div>
                </div>
              </div>
            </div>

            {/* Footer */}
            <div className="relative mt-10 border-t border-white/10 pt-4">
              <p className="text-[11px] text-white/45">
                Access is managed through your organization’s Microsoft
                account.
              </p>
            </div>
          </div>

          {/* Sign-in side */}
          <div className="flex min-h-[560px] flex-col justify-center p-7 sm:p-10 lg:px-12">
            {/* Mobile brand */}
            <div className="mb-10 flex items-center gap-3 lg:hidden">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-50">
                <MailIcon />
              </div>

              <div>
                <p className="text-[15px] font-semibold text-ink-900">
                  AI Email Assistant
                </p>

                <p className="text-[11px] text-ink-300">
                  Business email intelligence
                </p>
              </div>
            </div>

            {/* Heading */}
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.12em] text-brand-600">
                Workspace access
              </p>

              <h1 className="mt-3 text-2xl font-semibold tracking-tight text-ink-900 sm:text-[28px]">
                Sign in to continue
              </h1>

              <p className="mt-3 max-w-md text-sm leading-6 text-ink-500">
                Use your organization’s Microsoft account to access the
                dashboard, tasks, and workload information.
              </p>
            </div>

            {/* Sign-in failure banner */}
            {showAuthError && (
              <div className="mt-6 flex items-start gap-2.5 rounded-lg border border-red-200 bg-red-50 px-3.5 py-3">
                <svg
                  width="15"
                  height="15"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  className="mt-0.5 shrink-0 text-red-500"
                  aria-hidden="true"
                >
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 8v5" strokeLinecap="round" />
                  <path d="M12 16h.01" strokeLinecap="round" />
                </svg>

                <p className="text-xs leading-5 text-red-700">
                  We couldn’t sign you in with Microsoft. Please try again,
                  and contact your administrator if this keeps happening.
                </p>
              </div>
            )}

            {/* Microsoft sign-in */}
            <div className="mt-8">
              <a
                href={loginUrl}
                onClick={(event) => {
                  if (redirecting) {
                    event.preventDefault();
                    return;
                  }

                  setRedirecting(true);
                }}
                aria-disabled={redirecting}
                aria-busy={redirecting}
                className={`group flex w-full items-center justify-center gap-3 rounded-lg border px-4 py-3.5 text-sm font-semibold shadow-sm transition-all duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-200 focus-visible:ring-offset-2 ${
                  redirecting
                    ? "cursor-wait border-ink-900/10 bg-ink-900/[0.03] text-ink-300"
                    : "border-ink-900/10 bg-white text-ink-900 hover:border-ink-900/15 hover:bg-ink-900/[0.02] hover:shadow-md"
                }`}
              >
                {redirecting ? (
                  <>
                    <span
                      className="h-4 w-4 animate-spin rounded-full border-2 border-ink-300 border-t-transparent"
                      aria-hidden="true"
                    />

                    <span>Connecting to Microsoft…</span>
                  </>
                ) : (
                  <>
                    <MicrosoftLogo />

                    <span>Continue with Microsoft</span>
                  </>
                )}
              </a>
            </div>

            {/* Authentication context */}
            <div className="mt-5 rounded-lg border border-ink-900/5 bg-ink-50/60 px-3.5 py-3">
              <div className="flex gap-3">
                <div className="mt-0.5 shrink-0 text-ink-400">
                  <svg
                    width="15"
                    height="15"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    aria-hidden="true"
                  >
                    <path
                      d="M12 3l7 3v5c0 4.5-2.9 8.3-7 10-4.1-1.7-7-5.5-7-10V6l7-3z"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                </div>

                <div>
                  <p className="text-xs font-medium text-ink-700">
                    Microsoft sign-in
                  </p>

                  <p className="mt-0.5 text-[11px] leading-5 text-ink-400">
                    Authentication is handled by your organization’s
                    Microsoft identity system.
                  </p>
                </div>
              </div>
            </div>

            {/* Redirect information */}
            <p className="mt-5 text-center text-[11px] leading-5 text-ink-300">
              You’ll be redirected to Microsoft to authenticate and
              returned here automatically.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

function AuthLoadingScreen() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-[#f7f7fb]">
      <div className="flex items-center gap-3 text-sm text-ink-500">
        <span
          className="h-4 w-4 animate-spin rounded-full border-2 border-brand-200 border-t-brand-600"
          aria-hidden="true"
        />
        Loading workspace…
      </div>
    </div>
  );
}

function AppShell() {
  const { loading, unauthenticated } = useAppData();

  // Important:
  // Do not render the dashboard until the first authentication/data
  // initialization has completed. This prevents the dashboard from
  // flashing briefly before LoginGate is shown.
  if (loading) {
    return <AuthLoadingScreen />;
  }

  if (unauthenticated) {
    return <LoginGate />;
  }

  return (
    <div className="flex h-screen overflow-hidden bg-[#f7f7fb]">
      <Sidebar />

      <main className="flex-1 overflow-y-auto">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/tasks" element={<TasksPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </main>
    </div>
  );
}

export default function App() {
  return (
    <AppDataProvider>
      <AppShell />
    </AppDataProvider>
  );
}