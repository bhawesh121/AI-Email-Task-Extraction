import React, { useState } from "react";

export default function TasksByAssigneeCard({ data, loading, onViewAll }) {
  const max = Math.max(1, ...data.map((d) => d.count));
  const [hoveredName, setHoveredName] = useState(null);

  const getInitials = (name) =>
    name
      .split(" ")
      .map((part) => part.charAt(0))
      .join("")
      .slice(0, 2)
      .toUpperCase();

  const getRankStyle = (index) => {
    if (index === 0) {
      return "bg-brand-100 text-brand-700";
    }

    if (index === 1) {
      return "bg-indigo-50 text-indigo-600";
    }

    return "bg-ink-50 text-ink-500";
  };

  return (
    <div className="flex h-full min-h-[366px] flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card transition-shadow duration-200 hover:shadow-md">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h3 className="text-[15px] font-semibold text-ink-900">
            Tasks by Assignee
          </h3>
          <p className="mt-1 text-[11px] text-ink-300">
            Top 5 by assigned workload
          </p>
        </div>

        {!loading && data.length > 0 && (
          <span className="rounded-full bg-ink-50 px-2 py-1 text-[10px] font-medium text-ink-400">
            {data.length} shown
          </span>
        )}
      </div>

      {/* Content */}
      {loading ? (
        <div className="mt-5 space-y-4">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="flex items-center gap-3">
              <div className="h-8 w-8 animate-pulse rounded-full bg-ink-900/5" />
              <div className="flex-1 space-y-2">
                <div className="h-3 w-24 animate-pulse rounded bg-ink-900/5" />
                <div className="h-2.5 w-full animate-pulse rounded-full bg-ink-900/5" />
              </div>
              <div className="h-4 w-6 animate-pulse rounded bg-ink-900/5" />
            </div>
          ))}
        </div>
      ) : data.length === 0 ? (
        <div className="mt-8 flex flex-1 items-center justify-center">
          <div className="text-center">
            <div className="mx-auto flex h-10 w-10 items-center justify-center rounded-full bg-ink-50 text-sm font-semibold text-ink-300">
              —
            </div>
            <p className="mt-3 text-sm font-medium text-ink-700">
              No assigned tasks
            </p>
            <p className="mt-1 text-xs text-ink-300">
              Assigned workload will appear here.
            </p>
          </div>
        </div>
      ) : (
        <div className="mt-5 flex flex-col gap-2">
          {data.map((row, index) => {
            const percentage = Math.round((row.count / max) * 100);
            const isHovered = hoveredName === row.name;

            return (
              <div
                key={row.name}
                onMouseEnter={() => setHoveredName(row.name)}
                onMouseLeave={() => setHoveredName(null)}
                className={`group relative rounded-xl px-2.5 py-2 transition-all duration-200 ${
                  isHovered
                    ? "bg-ink-50/80"
                    : "bg-transparent hover:bg-ink-50/50"
                }`}
              >
                <div className="flex items-center gap-3">
                  {/* Rank */}
                  <div
                    className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-[10px] font-bold transition-transform duration-200 ${
                      getRankStyle(index)
                    } ${isHovered ? "scale-110" : ""}`}
                  >
                    {index + 1}
                  </div>

                  {/* Avatar + name */}
                  <div className="flex min-w-0 w-[92px] shrink-0 items-center gap-2">
                    <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-white text-[9px] font-semibold text-brand-600 ring-1 ring-ink-900/5">
                      {getInitials(row.name)}
                    </div>

                    <span className="truncate text-sm font-medium text-ink-700">
                      {row.name}
                    </span>
                  </div>

                  {/* Bar */}
                  <div className="relative min-w-0 flex-1">
                    <div className="h-2.5 overflow-hidden rounded-full bg-ink-900/5">
                      <div
                        className="h-full rounded-full bg-brand-500 transition-all duration-500 ease-out group-hover:bg-brand-600"
                        style={{ width: `${percentage}%` }}
                      />
                    </div>

                    {/* Hover percentage */}
                    <div
                      className={`pointer-events-none absolute -top-7 left-1/2 -translate-x-1/2 rounded-md bg-ink-900 px-2 py-1 text-[10px] font-medium text-white shadow-sm transition-all duration-150 ${
                        isHovered
                          ? "translate-y-0 opacity-100"
                          : "translate-y-1 opacity-0"
                      }`}
                    >
                      {percentage}% of top workload
                    </div>
                  </div>

                  {/* Count */}
                  <div className="w-8 shrink-0 text-right">
                    <span
                      className={`text-sm font-semibold tabular-nums transition-colors ${
                        isHovered ? "text-brand-600" : "text-ink-900"
                      }`}
                    >
                      {row.count}
                    </span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Footer */}
      <div className="mt-auto border-t border-ink-900/5 pt-4">
        <button
          type="button"
          onClick={onViewAll}
          className="group flex items-center gap-1.5 text-sm font-medium text-brand-600 transition-colors hover:text-brand-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-200 focus-visible:ring-offset-2 rounded-md"
        >
          <span>View all assignees</span>

          <span className="inline-block transition-transform duration-200 group-hover:translate-x-1">
            →
          </span>
        </button>
      </div>
    </div>
  );
}