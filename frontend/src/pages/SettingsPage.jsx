import React from "react";
import { useAppData } from "../lib/AppDataContext";
import {
  MailIcon,
  CheckCircleIcon,
  UserIcon,
} from "../components/Icons";

function signOut() {
  window.location.assign("/sla/logout");
}

function getSourceTypeStyle(type) {
  switch (type) {
    case "EXTERNAL":
      return "bg-amber-50 text-amber-700";
    case "MICROSOFT":
      return "bg-blue-50 text-blue-700";
    case "INTERNAL":
      return "bg-emerald-50 text-emerald-700";
    default:
      return "bg-ink-50 text-ink-500";
  }
}

export default function SettingsPage() {
  const { mailboxes, emailSources, loading } = useAppData();

  return (
    <div className="space-y-5 p-6">
      {/* Page header */}
      <div>
        <h1 className="text-2xl font-bold text-ink-900">
          Settings
        </h1>

        <p className="mt-1 text-sm text-ink-500">
          Manage connected mailboxes and email source information
        </p>
      </div>

      {/* Connected Mailboxes */}
      <section className="rounded-2xl border border-ink-900/5 bg-white shadow-card">
        <div className="border-b border-ink-900/5 px-5 py-4">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-[15px] font-semibold text-ink-900">
                Connected Mailboxes
              </h2>

              <p className="mt-1 text-xs text-ink-300">
                Mailboxes currently connected to the assistant
              </p>
            </div>

            {!loading && (
              <span className="text-xs text-ink-300">
                {mailboxes.length}{" "}
                {mailboxes.length === 1 ? "mailbox" : "mailboxes"}
              </span>
            )}
          </div>
        </div>

        <div className="px-5">
          {loading &&
            [...Array(2)].map((_, i) => (
              <div
                key={i}
                className="flex items-center gap-3 border-b border-ink-900/5 py-4 last:border-0"
              >
                <div className="h-10 w-10 animate-pulse rounded-lg bg-ink-900/5" />

                <div className="flex-1 space-y-2">
                  <div className="h-3.5 w-28 animate-pulse rounded bg-ink-900/5" />
                  <div className="h-3 w-56 animate-pulse rounded bg-ink-900/5" />
                </div>
              </div>
            ))}

          {!loading && mailboxes.length === 0 && (
            <div className="py-10 text-center">
              <p className="text-sm font-medium text-ink-700">
                No mailboxes connected
              </p>

              <p className="mt-1 text-xs text-ink-300">
                Connected mailboxes will appear here.
              </p>
            </div>
          )}

          {!loading &&
            mailboxes.map((mb) => (
              <div
                key={mb.id}
                className="flex items-center justify-between gap-4 border-b border-ink-900/5 py-4 last:border-0"
              >
                <div className="flex min-w-0 items-center gap-3">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-brand-50">
                    <MailIcon />
                  </div>

                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-ink-900">
                      {mb.displayName}
                    </p>

                    <p
                      title={mb.email}
                      className="mt-0.5 truncate text-xs text-ink-500"
                    >
                      {mb.email}
                    </p>
                  </div>
                </div>

                <div className="flex shrink-0 items-center gap-1.5 text-xs font-medium text-emerald-600">
                  <CheckCircleIcon width={14} height={14} />
                  Connected
                </div>
              </div>
            ))}
        </div>
      </section>

      {/* Email Sources */}
      <section className="rounded-2xl border border-ink-900/5 bg-white shadow-card">
        <div className="border-b border-ink-900/5 px-5 py-4">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-[15px] font-semibold text-ink-900">
                Email Sources
              </h2>

              <p className="mt-1 text-xs text-ink-300">
                Email activity grouped by sender domain
              </p>
            </div>

            {!loading && (
              <span className="text-xs text-ink-300">
                {emailSources.length}{" "}
                {emailSources.length === 1 ? "domain" : "domains"}
              </span>
            )}
          </div>
        </div>

        <div className="overflow-x-auto px-5">
          <table className="w-full min-w-[620px] text-sm">
            <thead>
              <tr className="border-b border-ink-900/5 text-left text-[11px] uppercase tracking-wide text-ink-300">
                <th className="py-3 font-medium">
                  Domain
                </th>

                <th className="py-3 font-medium">
                  Type
                </th>

                <th className="py-3 text-right font-medium">
                  Emails
                </th>
              </tr>
            </thead>

            <tbody>
              {loading &&
                [...Array(4)].map((_, i) => (
                  <tr key={i}>
                    <td colSpan={3} className="py-3">
                      <div className="h-8 animate-pulse rounded bg-ink-900/5" />
                    </td>
                  </tr>
                ))}

              {!loading && emailSources.length === 0 && (
                <tr>
                  <td
                    colSpan={3}
                    className="py-10 text-center text-sm text-ink-300"
                  >
                    No email sources available.
                  </td>
                </tr>
              )}

              {!loading &&
                [...emailSources]
                  .sort((a, b) => b.emailCount - a.emailCount)
                  .map((source) => (
                    <tr
                      key={source.domain}
                      className="border-b border-ink-900/5 last:border-0"
                    >
                      <td className="py-3.5 font-medium text-ink-900">
                        {source.domain}
                      </td>

                      <td className="py-3.5">
                        <span
                          className={`inline-flex rounded-md px-2 py-1 text-[10px] font-medium ${getSourceTypeStyle(
                            source.sourceType
                          )}`}
                        >
                          {source.sourceType}
                        </span>
                      </td>

                      <td className="py-3.5 text-right font-semibold tabular-nums text-ink-900">
                        {source.emailCount.toLocaleString()}
                      </td>
                    </tr>
                  ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* Account & Session */}
      <section className="rounded-2xl border border-ink-900/5 bg-white shadow-card">
        <div className="flex items-center justify-between gap-6 px-5 py-5">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-ink-50 text-ink-500">
              <UserIcon />
            </div>

            <div>
              <h2 className="text-[15px] font-semibold text-ink-900">
                Account & Session
              </h2>

              <p className="mt-1 text-xs text-ink-300">
                Your Microsoft account is used to access this application.
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={signOut}
            className="shrink-0 rounded-lg border border-ink-900/10 bg-white px-4 py-2 text-sm font-medium text-ink-700 transition-colors hover:bg-ink-900/[0.03] hover:text-ink-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-200"
          >
            Sign out
          </button>
        </div>
      </section>
    </div>
  );
}