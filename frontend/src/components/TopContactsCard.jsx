import React from "react";

function initials(name) {
  if (!name) return "?";
  return name.split(" ").map((p) => p[0]).join("").slice(0, 2).toUpperCase();
}

export default function TopContactsCard({ data, loading }) {
  const max = Math.max(1, ...data.map((d) => d.count));

  return (
    <div className="flex flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      <h3 className="text-[15px] font-semibold text-ink-900">Top External Contacts</h3>
      <p className="text-xs text-ink-300">By email volume</p>

      {loading ? (
        <div className="mt-4 space-y-3">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="h-6 animate-pulse rounded bg-ink-900/5" />
          ))}
        </div>
      ) : data.length === 0 ? (
        <p className="mt-6 text-sm text-ink-300">No external email activity yet.</p>
      ) : (
        <div className="mt-4 flex flex-col gap-3">
          {data.map((c) => (
            <div key={c.email || c.name} className="flex items-center gap-3">
              <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-ink-900/[0.04] text-[11px] font-semibold text-ink-700">
                {initials(c.name)}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm text-ink-900">{c.name}</p>
                {c.domain && <p className="truncate text-xs text-ink-300">{c.domain}</p>}
              </div>
              <div className="h-2 w-16 shrink-0 rounded-full bg-ink-900/5">
                <div
                  className="h-2 rounded-full bg-brand-500"
                  style={{ width: `${(c.count / max) * 100}%` }}
                />
              </div>
              <span className="w-6 shrink-0 text-right text-sm font-semibold text-ink-900">{c.count}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
