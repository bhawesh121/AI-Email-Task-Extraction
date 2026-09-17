import React from "react";

function initials(name) {
  if (!name) return "?";
  return name.split(" ").map((p) => p[0]).join("").slice(0, 2).toUpperCase();
}

export default function EmployeeWorkloadCard({ data, loading }) {
  const maxOpen = Math.max(1, ...data.map((d) => d.open));

  return (
    <div className="rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      <div className="flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold text-ink-900">Employee Workload</h3>
        <span className="text-xs text-ink-300">Open tasks by assignee</span>
      </div>

      {loading ? (
        <div className="mt-4 space-y-3">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="h-10 animate-pulse rounded bg-ink-900/5" />
          ))}
        </div>
      ) : data.length === 0 ? (
        <p className="mt-6 text-sm text-ink-300">No assigned tasks yet.</p>
      ) : (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full min-w-[560px] text-sm">
            <thead>
              <tr className="text-left text-xs text-ink-300">
                <th className="pb-2 font-medium">Employee</th>
                <th className="pb-2 font-medium">Workload</th>
                <th className="pb-2 pl-4 text-right font-medium">Open</th>
                <th className="pb-2 pl-4 text-right font-medium">Overdue</th>
                <th className="pb-2 pl-4 text-right font-medium">High Priority</th>
              </tr>
            </thead>
            <tbody>
              {data.map((row) => (
                <tr key={row.name} className="border-t border-ink-900/5">
                  <td className="py-2.5">
                    <div className="flex items-center gap-2">
                      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-100 text-[11px] font-semibold text-brand-700">
                        {initials(row.name)}
                      </div>
                      <span className="truncate text-ink-900">{row.name}</span>
                    </div>
                  </td>
                  <td className="py-2.5 pr-4">
                    <div className="h-2 w-full min-w-[100px] rounded-full bg-ink-900/5">
                      <div
                        className="h-2 rounded-full bg-brand-500"
                        style={{ width: `${(row.open / maxOpen) * 100}%` }}
                      />
                    </div>
                  </td>
                  <td className="py-2.5 pl-4 text-right font-medium text-ink-900">{row.open}</td>
                  <td className={`py-2.5 pl-4 text-right font-medium ${row.overdue > 0 ? "text-red-600" : "text-ink-300"}`}>
                    {row.overdue}
                  </td>
                  <td className={`py-2.5 pl-4 text-right font-medium ${row.highPriority > 0 ? "text-amber-600" : "text-ink-300"}`}>
                    {row.highPriority}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
