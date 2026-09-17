import React from "react";

export default function StatCard({ icon, iconBg, label, value, delta, loading }) {
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      <div className="flex items-center gap-2.5">
        <div
          className="flex h-9 w-9 items-center justify-center rounded-lg"
          style={{ background: iconBg }}
        >
          {icon}
        </div>
        <span className="text-sm font-medium text-ink-700">{label}</span>
      </div>

      {loading ? (
        <div className="h-8 w-20 animate-pulse rounded bg-ink-900/5" />
      ) : (
        <div className="flex items-baseline gap-2">
          <span className="text-[26px] font-bold leading-none text-ink-900">{value}</span>
          {delta !== undefined && delta !== null && (
            <span
              className={`flex items-center gap-0.5 text-xs font-semibold ${
                delta >= 0 ? "text-emerald-600" : "text-red-500"
              }`}
            >
              {delta >= 0 ? "↑" : "↓"} {Math.abs(delta)}%
            </span>
          )}
        </div>
      )}
      <span className="text-xs text-ink-300">vs previous week</span>
    </div>
  );
}
