import React, { useState } from "react";
import { formatDate } from "../lib/derive";

export default function AttentionQueueCard({
  overdueCount,
  highPriorityCount,
  items,
  loading,
  onOpenTask,
  onViewOverdue,
  onViewHighPriority,
}) {
  const [hoveredTask, setHoveredTask] = useState(null);

  return (
    <div className="flex flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-[15px] font-semibold text-ink-900">
            Needs Attention
          </h3>
          <p className="mt-1 text-[11px] text-ink-300">
            Tasks requiring review
          </p>
        </div>

        {!loading && items.length > 0 && (
          <span className="text-xs text-ink-300">
            {items.length} items
          </span>
        )}
      </div>

      {/* Summary filters */}
      <div className="mt-4 flex gap-2">
        <button
          type="button"
          onClick={onViewOverdue}
          className="flex items-center gap-2 rounded-lg border border-red-100 bg-red-50/60 px-3 py-1.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-200"
        >
          <span className="h-1.5 w-1.5 rounded-full bg-red-500" />
          <span>{loading ? "…" : overdueCount}</span>
          <span>overdue</span>
        </button>

        <button
          type="button"
          onClick={onViewHighPriority}
          className="flex items-center gap-2 rounded-lg border border-amber-100 bg-amber-50/60 px-3 py-1.5 text-xs font-medium text-amber-700 transition-colors hover:bg-amber-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber-200"
        >
          <span className="h-1.5 w-1.5 rounded-full bg-amber-500" />
          <span>{loading ? "…" : highPriorityCount}</span>
          <span>high priority</span>
        </button>
      </div>

      {/* Task list */}
      <div className="mt-4 divide-y divide-ink-900/5">
        {loading &&
          [...Array(4)].map((_, i) => (
            <div key={i} className="py-3">
              <div className="animate-pulse">
                <div className="h-3.5 w-3/4 rounded bg-ink-900/5" />
                <div className="mt-2 h-3 w-20 rounded bg-ink-900/5" />
              </div>
            </div>
          ))}

        {!loading && items.length === 0 && (
          <div className="py-8 text-center">
            <p className="text-sm font-medium text-ink-700">
              Nothing needs attention
            </p>
            <p className="mt-1 text-xs text-ink-300">
              There are no overdue or high-priority tasks.
            </p>
          </div>
        )}

        {!loading &&
          items.map((task) => {
            const isHovered = hoveredTask === task.id;
            const isOverdue = task._reason === "overdue";

            return (
              <button
                key={task.id}
                type="button"
                onClick={() => onOpenTask(task)}
                onMouseEnter={() => setHoveredTask(task.id)}
                onMouseLeave={() => setHoveredTask(null)}
                onFocus={() => setHoveredTask(task.id)}
                onBlur={() => setHoveredTask(null)}
                className={`flex w-full items-center gap-4 py-3 text-left transition-colors focus:outline-none ${
                  isHovered ? "bg-ink-900/[0.015]" : ""
                }`}
              >
                {/* Main content */}
                <div className="min-w-0 flex-1">
                  <p
                    title={task.title}
                    className="truncate text-sm font-medium text-ink-900"
                  >
                    {task.title}
                  </p>

                  <p className="mt-1 truncate text-xs text-ink-300">
                    {task.assignee || "Unassigned"}
                  </p>
                </div>

                {/* Reason */}
                <div className="flex shrink-0 items-center gap-2">
                  <span
                    className={`h-1.5 w-1.5 rounded-full ${
                      isOverdue ? "bg-red-500" : "bg-amber-500"
                    }`}
                  />

                  <span
                    className={`text-xs font-medium ${
                      isOverdue ? "text-red-600" : "text-amber-700"
                    }`}
                  >
                    {isOverdue
                      ? `Due ${formatDate(task.dueDate)}`
                      : "High priority"}
                  </span>

                  <span
                    className={`text-xs text-ink-300 transition-opacity ${
                      isHovered ? "opacity-100" : "opacity-0"
                    }`}
                  >
                    →
                  </span>
                </div>
              </button>
            );
          })}
      </div>
    </div>
  );
}