import React from "react";
import { NavLink } from "react-router-dom";

const NavIcon = ({ path }) => (
  <svg
    width="18"
    height="18"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
  >
    <path
      d={path}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

const ICONS = {
  dashboard:
    "M4 4h6v8H4V4zm10 0h6v5h-6V4zM4 16h6v4H4v-4zm10 3h6v-8h-6v8z",

  tasks:
    "M9 5h6M9 12h6M9 19h6M5 5h.01M5 12h.01M5 19h.01",

  settings:
    "M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 11-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 11-4 0v-.09a1.65 1.65 0 00-1-1.51 1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 11-2.83-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 110-4h.09a1.65 1.65 0 001.51-1 1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 112.83-2.83l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 114 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 112.83 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 110 4h-.09a1.65 1.65 0 00-1.51 1z",

  mail:
    "M4 4h16v16H4V4zm0 0l8 8 8-8",

  logout:
    "M9 5H5a2 2 0 00-2 2v10a2 2 0 002 2h4M16 17l5-5-5-5M21 12H9",
};

export default function Sidebar() {
  const navClass = ({ isActive }) =>
    `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
      isActive
        ? "bg-brand-50 text-brand-700"
        : "text-ink-500 hover:bg-ink-900/5 hover:text-ink-900"
    }`;

  const signOut = () => {
    window.location.assign("/sla/logout");
  };

  return (
    <aside className="hidden h-screen w-60 shrink-0 flex-col overflow-y-auto border-r border-ink-900/5 bg-white px-3 py-4 md:flex">
      {/* Brand */}
      <div className="flex items-center gap-2 px-2 pb-6">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-brand-500 text-white">
          <NavIcon path={ICONS.mail} />
        </div>

        <span className="text-[15px] font-semibold text-ink-900">
          AI Email Assistant
        </span>
      </div>

      {/* Main navigation */}
      <nav className="flex flex-col gap-1">
        <NavLink
          to="/"
          end
          className={navClass}
        >
          <NavIcon path={ICONS.dashboard} />
          Dashboard
        </NavLink>

        <NavLink
          to="/tasks"
          className={navClass}
        >
          <NavIcon path={ICONS.tasks} />
          Tasks & Workload
        </NavLink>
      </nav>

      {/* System */}
      <div className="mt-8 px-3 text-[11px] font-semibold uppercase tracking-wide text-ink-300">
        System
      </div>

      <nav className="mt-1 flex flex-col gap-1">
        <NavLink
          to="/settings"
          className={navClass}
        >
          <NavIcon path={ICONS.settings} />
          Settings
        </NavLink>
      </nav>

      {/* Bottom actions */}
      <div className="mt-auto border-t border-ink-900/5 pt-3">
        <button
          type="button"
          onClick={signOut}
          className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-ink-500 transition-colors hover:bg-red-50 hover:text-red-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-200"
        >
          <NavIcon path={ICONS.logout} />
          Sign out
        </button>
      </div>
    </aside>
  );
}