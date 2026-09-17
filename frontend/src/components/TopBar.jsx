import React, { useRef, useState } from "react";
import { RefreshIcon, FilterIcon, CalendarIcon } from "./Icons";
import { useAppData } from "../lib/AppDataContext";

function useClickOutside(ref, onOutside) {
  React.useEffect(() => {
    function handler(e) {
      if (ref.current && !ref.current.contains(e.target)) onOutside();
    }
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [ref, onOutside]);
}

const STATUS_OPTIONS = ["", "NEW", "IN_PROGRESS", "COMPLETED", "BLOCKED", "ARCHIVED"];
const PRIORITY_OPTIONS = ["", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
const RANGE_OPTIONS = [
  { label: "Last 7 days", value: 7 },
  { label: "Last 14 days", value: 14 },
  { label: "Last 30 days", value: 30 },
];

export default function TopBar({ title, subtitle }) {
  const { refresh, refreshing, filters, setFilters, trendDays, setTrendDays } = useAppData();
  const [rangeOpen, setRangeOpen] = useState(false);
  const [filterOpen, setFilterOpen] = useState(false);
  const rangeRef = useRef(null);
  const filterRef = useRef(null);

  useClickOutside(rangeRef, () => setRangeOpen(false));
  useClickOutside(filterRef, () => setFilterOpen(false));

  const activeFilterCount = Object.values(filters).filter(Boolean).length;
  const rangeLabel = RANGE_OPTIONS.find((r) => r.value === trendDays)?.label ?? "Last 7 days";

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h1 className="text-2xl font-bold text-ink-900">{title}</h1>
        {subtitle && <p className="text-sm text-ink-500">{subtitle}</p>}
      </div>

      <div className="flex items-center gap-2">
        <div className="relative" ref={rangeRef}>
          <button
            onClick={() => setRangeOpen((o) => !o)}
            className="flex items-center gap-2 rounded-lg border border-ink-900/10 bg-white px-3 py-2 text-sm text-ink-700 hover:bg-ink-900/5"
          >
            <CalendarIcon width={16} height={16} />
            {rangeLabel}
          </button>
          {rangeOpen && (
            <div className="absolute right-0 z-20 mt-1 w-40 overflow-hidden rounded-lg border border-ink-900/10 bg-white shadow-card">
              {RANGE_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => {
                    setTrendDays(opt.value);
                    setRangeOpen(false);
                  }}
                  className={`block w-full px-3 py-2 text-left text-sm hover:bg-ink-900/5 ${
                    trendDays === opt.value ? "font-semibold text-brand-600" : "text-ink-700"
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          )}
        </div>

        <button
          onClick={refresh}
          className="flex h-9 w-9 items-center justify-center rounded-lg border border-ink-900/10 bg-white text-ink-700 hover:bg-ink-900/5"
          title="Refresh"
        >
          <RefreshIcon width={16} height={16} className={refreshing ? "animate-spin" : ""} />
        </button>

        <div className="relative" ref={filterRef}>
          <button
            onClick={() => setFilterOpen((o) => !o)}
            className="flex items-center gap-2 rounded-lg border border-ink-900/10 bg-white px-3 py-2 text-sm text-ink-700 hover:bg-ink-900/5"
          >
            <FilterIcon width={16} height={16} />
            Filters
            {activeFilterCount > 0 && (
              <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-brand-500 px-1 text-[10px] font-bold text-white">
                {activeFilterCount}
              </span>
            )}
          </button>
          {filterOpen && (
            <div className="absolute right-0 z-20 mt-1 w-56 rounded-lg border border-ink-900/10 bg-white p-3 shadow-card">
              <label className="block text-xs font-medium text-ink-500">Status</label>
              <select
                value={filters.status}
                onChange={(e) => setFilters((f) => ({ ...f, status: e.target.value }))}
                className="mt-1 w-full rounded-md border border-ink-900/10 px-2 py-1.5 text-sm"
              >
                {STATUS_OPTIONS.map((s) => (
                  <option key={s} value={s}>
                    {s === "" ? "All statuses" : s.replace("_", " ")}
                  </option>
                ))}
              </select>

              <label className="mt-3 block text-xs font-medium text-ink-500">Priority</label>
              <select
                value={filters.priority}
                onChange={(e) => setFilters((f) => ({ ...f, priority: e.target.value }))}
                className="mt-1 w-full rounded-md border border-ink-900/10 px-2 py-1.5 text-sm"
              >
                {PRIORITY_OPTIONS.map((p) => (
                  <option key={p} value={p}>
                    {p === "" ? "All priorities" : p.charAt(0) + p.slice(1).toLowerCase()}
                  </option>
                ))}
              </select>

              {activeFilterCount > 0 && (
                <button
                  onClick={() => setFilters({ status: "", priority: "" })}
                  className="mt-3 text-xs font-medium text-brand-600 hover:text-brand-700"
                >
                  Clear filters
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
