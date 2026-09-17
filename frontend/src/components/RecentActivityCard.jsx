import React, { useState } from "react";

const STATUS_LABEL = {
  NEW: "Created",
  IN_PROGRESS: "Moved to in progress",
  COMPLETED: "Completed",
  BLOCKED: "Blocked",
  ARCHIVED: "Archived",
};

const STATUS_STYLE = {
  NEW: {
    dot: "bg-brand-500",
    text: "text-brand-600",
    bg: "bg-brand-50",
  },
  IN_PROGRESS: {
    dot: "bg-blue-500",
    text: "text-blue-600",
    bg: "bg-blue-50",
  },
  COMPLETED: {
    dot: "bg-emerald-500",
    text: "text-emerald-600",
    bg: "bg-emerald-50",
  },
  BLOCKED: {
    dot: "bg-red-500",
    text: "text-red-600",
    bg: "bg-red-50",
  },
  ARCHIVED: {
    dot: "bg-ink-300",
    text: "text-ink-500",
    bg: "bg-ink-50",
  },
};

function timeAgo(dateStr) {
  const diffMs = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diffMs / 60000);

  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;

  const hrs = Math.floor(mins / 60);

  if (hrs < 24) return `${hrs}h ago`;

  return `${Math.floor(hrs / 24)}d ago`;
}

function formatDateTime(dateStr) {
  if (!dateStr) return "";

  const date = new Date(dateStr);

  return date.toLocaleString(undefined, {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export default function RecentActivityCard({
  tasks,
  loading,
  onViewAll,
}) {
  const [expandedId, setExpandedId] = useState(null);

  const recent = [...tasks]
    .filter((t) => t.updatedAt)
    .sort(
      (a, b) =>
        new Date(b.updatedAt) - new Date(a.updatedAt)
    )
    .slice(0, 6);

  const toggleExpanded = (id) => {
    setExpandedId((current) =>
      current === id ? null : id
    );
  };

  return (
    <div className="flex min-h-[366px] flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h3 className="text-[15px] font-semibold text-ink-900">
            Recent Activity
          </h3>

          <p className="mt-1 text-[11px] text-ink-300">
            Most recently updated tasks
          </p>
        </div>

        {!loading && recent.length > 0 && (
          <span className="text-xs text-ink-300">
            Latest {recent.length}
          </span>
        )}
      </div>

      {/* Activity */}
      <div className="mt-4 flex-1">
        {loading ? (
          <div className="space-y-3">
            {[...Array(6)].map((_, i) => (
              <div
                key={i}
                className="flex items-center gap-3 py-2"
              >
                <div className="h-2 w-2 animate-pulse rounded-full bg-ink-900/10" />

                <div className="min-w-0 flex-1">
                  <div className="h-3.5 w-3/4 animate-pulse rounded bg-ink-900/5" />
                  <div className="mt-1.5 h-2.5 w-20 animate-pulse rounded bg-ink-900/5" />
                </div>
              </div>
            ))}
          </div>
        ) : recent.length === 0 ? (
          <div className="flex h-full items-center justify-center">
            <div className="text-center">
              <p className="text-sm font-medium text-ink-700">
                No recent task activity
              </p>

              <p className="mt-1 text-xs text-ink-300">
                Updates will appear here as tasks change.
              </p>
            </div>
          </div>
        ) : (
          <div className="relative">
            {/* Timeline */}
            <div className="absolute bottom-2 left-[5px] top-2 w-px bg-ink-900/5" />

            <div className="space-y-1">
              {recent.map((t) => {
                const isExpanded = expandedId === t.id;

                const status =
                  STATUS_STYLE[t.status] ||
                  STATUS_STYLE.ARCHIVED;

                const statusLabel =
                  STATUS_LABEL[t.status] || "Updated";

                return (
                  <div key={t.id} className="relative">
                    <button
                      type="button"
                      onClick={() => toggleExpanded(t.id)}
                      className={`group flex w-full items-start gap-3 rounded-lg py-2.5 pl-0 pr-2 text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-200 ${
                        isExpanded
                          ? "bg-ink-900/[0.02]"
                          : "hover:bg-ink-900/[0.015]"
                      }`}
                    >
                      {/* Timeline dot */}
                      <span
                        className={`relative z-10 mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full border-2 border-white ${status.dot}`}
                      />

                      {/* Activity content */}
                      <div className="min-w-0 flex-1">
                        <div className="flex items-start justify-between gap-3">
                          <p
                            title={`${t.title} — ${statusLabel}`}
                            className={`min-w-0 flex-1 truncate text-[13px] font-medium ${
                              isExpanded
                                ? "text-brand-600"
                                : "text-ink-900"
                            }`}
                          >
                            {t.title}
                          </p>

                          <span className="shrink-0 text-[11px] text-ink-300">
                            {timeAgo(t.updatedAt)}
                          </span>
                        </div>

                        <div className="mt-1 flex items-center gap-2">
                          <span
                            className={`inline-flex rounded-md px-1.5 py-0.5 text-[10px] font-medium ${status.bg} ${status.text}`}
                          >
                            {statusLabel}
                          </span>
                        </div>

                        {/* Expanded information */}
                        {isExpanded && (
                          <div className="mt-2 rounded-lg border border-ink-900/5 bg-ink-50/60 px-3 py-2.5">
                            <div className="grid grid-cols-2 gap-x-4 gap-y-2">
                              <div>
                                <p className="text-[9px] font-medium uppercase tracking-wide text-ink-300">
                                  Updated
                                </p>

                                <p className="mt-0.5 text-[11px] text-ink-600">
                                  {formatDateTime(t.updatedAt)}
                                </p>
                              </div>

                              <div>
                                <p className="text-[9px] font-medium uppercase tracking-wide text-ink-300">
                                  Status
                                </p>

                                <p className="mt-0.5 text-[11px] text-ink-600">
                                  {statusLabel}
                                </p>
                              </div>

                              {t.assignee && (
                                <div className="col-span-2">
                                  <p className="text-[9px] font-medium uppercase tracking-wide text-ink-300">
                                    Assignee
                                  </p>

                                  <p className="mt-0.5 truncate text-[11px] text-ink-600">
                                    {t.assignee}
                                  </p>
                                </div>
                              )}
                            </div>
                          </div>
                        )}
                      </div>

                      {/* Expand indicator */}
                      <span
                        className={`mt-1 text-[11px] text-ink-300 transition-transform duration-150 ${
                          isExpanded ? "rotate-90" : ""
                        }`}
                      >
                        ›
                      </span>
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="mt-3 border-t border-ink-900/5 pt-3">
        <button
          type="button"
          onClick={onViewAll}
          className="flex items-center gap-1.5 rounded-md text-sm font-medium text-brand-600 transition-colors hover:text-brand-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-200 focus-visible:ring-offset-2"
        >
          <span>View all activity</span>

          <span className="transition-transform duration-150 group-hover:translate-x-0.5">
            →
          </span>
        </button>
      </div>
    </div>
  );
}