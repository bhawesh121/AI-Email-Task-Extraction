import React, { useState } from "react";

// This component intentionally keeps the existing business logic:
// - checks are supplied by the parent
// - loading state is supplied by the parent
// - c.ok determines Reachable / Unreachable
// - c.detail remains the technical detail
export default function SystemStatusCard({ checks, loading }) {
  const [expanded, setExpanded] = useState(null);

  const reachableCount = checks.filter((c) => c.ok).length;
  const totalCount = checks.length;
  const hasIssues = checks.some((c) => !c.ok);

  const toggleRow = (label) => {
    setExpanded((current) => (current === label ? null : label));
  };

  return (
    <div className="flex flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h3 className="text-[15px] font-semibold text-ink-900">
            System Status
          </h3>

          <p className="mt-1 text-[11px] text-ink-300">
            Live availability of dashboard services
          </p>
        </div>

        {!loading && totalCount > 0 && (
          <div
            className={`flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[10px] font-medium ${
              hasIssues
                ? "bg-red-50 text-red-600"
                : "bg-emerald-50 text-emerald-600"
            }`}
          >
            <span
              className={`h-1.5 w-1.5 rounded-full ${
                hasIssues ? "bg-red-500" : "bg-emerald-500"
              }`}
            />

            {hasIssues ? "Attention required" : "All systems operational"}
          </div>
        )}
      </div>

      {/* Summary */}
      {!loading && totalCount > 0 && (
        <div className="mt-4 flex items-center gap-4 border-b border-ink-900/5 pb-3">
          <div>
            <span className="text-lg font-semibold tabular-nums text-ink-900">
              {reachableCount}
            </span>

            <span className="ml-1 text-xs text-ink-300">
              / {totalCount} reachable
            </span>
          </div>

          {hasIssues && (
            <span className="text-xs text-red-500">
              {totalCount - reachableCount} unavailable
            </span>
          )}
        </div>
      )}

      {/* Service rows */}
      <div className="mt-1 divide-y divide-ink-900/5">
        {loading ? (
          [...Array(Math.max(checks.length, 3))].map((_, index) => (
            <div
              key={index}
              className="flex items-center justify-between py-3"
            >
              <div className="h-3.5 w-32 animate-pulse rounded bg-ink-900/5" />

              <div className="h-3 w-16 animate-pulse rounded bg-ink-900/5" />
            </div>
          ))
        ) : checks.length === 0 ? (
          <div className="py-8 text-center">
            <p className="text-sm font-medium text-ink-700">
              No status checks available
            </p>

            <p className="mt-1 text-xs text-ink-300">
              Service availability will appear here when checks are returned.
            </p>
          </div>
        ) : (
          checks.map((c) => {
            const isExpanded = expanded === c.label;

            return (
              <div key={c.label}>
                {/* Main row */}
                <button
                  type="button"
                  onClick={() => toggleRow(c.label)}
                  className="flex w-full items-center justify-between gap-4 py-3 text-left transition-colors hover:bg-ink-900/[0.02] focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-200"
                >
                  {/* Service */}
                  <div className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium text-ink-700">
                      {c.label}
                    </span>
                  </div>

                  {/* Status */}
                  <div className="flex shrink-0 items-center gap-2">
                    <span
                      className={`h-2 w-2 rounded-full ${
                        c.ok ? "bg-emerald-500" : "bg-red-500"
                      }`}
                    />

                    <span
                      className={`text-xs font-medium ${
                        c.ok ? "text-emerald-600" : "text-red-500"
                      }`}
                    >
                      {c.ok ? "Reachable" : "Unreachable"}
                    </span>

                    {/* Expand indicator */}
                    <span
                      className={`ml-1 text-[11px] text-ink-300 transition-transform duration-150 ${
                        isExpanded ? "rotate-90" : ""
                      }`}
                    >
                      ›
                    </span>
                  </div>
                </button>

                {/* Technical detail */}
                {isExpanded && c.detail && (
                  <div className="mb-2 rounded-lg bg-ink-50 px-3 py-2.5">
                    <p className="mb-1 text-[10px] font-medium uppercase tracking-wide text-ink-300">
                      Technical detail
                    </p>

                    <p className="break-words text-xs leading-5 text-ink-500">
                      {c.detail}
                    </p>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>

      {/* Footer */}
      {!loading && checks.length > 0 && (
        <p className="mt-3 border-t border-ink-900/5 pt-3 text-[10px] text-ink-300">
          Select a service to view its technical status.
        </p>
      )}
    </div>
  );
}