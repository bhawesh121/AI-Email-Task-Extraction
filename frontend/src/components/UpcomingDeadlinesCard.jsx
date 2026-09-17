import React from "react";
import { formatDate, priorityDot } from "../lib/derive";

export default function UpcomingDeadlinesCard({
  tasks,
  loading,
  onViewAll,
  onOpenTask,
}) {
  return (
    <div className="flex flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h3 className="text-[15px] font-semibold text-ink-900">
            Upcoming Deadlines
          </h3>

          <p className="mt-1 text-[11px] text-ink-300">
            Tasks approaching their due date
          </p>
        </div>

        {!loading && tasks.length > 0 && (
          <span className="text-[10px] font-medium text-ink-700">
            {tasks.length} {tasks.length === 1 ? "task" : "tasks"}
          </span>
        )}
      </div>

      {/* Content */}
      <div className="mt-4">
        {loading ? (
          <div className="space-y-3">
            {[...Array(5)].map((_, index) => (
              <div
                key={index}
                className="h-12 animate-pulse rounded-xl bg-ink-900/5"
              />
            ))}
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex min-h-[190px] items-center justify-center">
            <div className="text-center">
              <p className="text-sm font-medium text-ink-700">
                Nothing due soon
              </p>

              <p className="mt-1 text-xs text-ink-300">
                Upcoming task deadlines will appear here.
              </p>
            </div>
          </div>
        ) : (
          <div className="space-y-2">
            {tasks.map((task) => {
              const priority = task.priority
                ? task.priority.charAt(0) +
                  task.priority.slice(1).toLowerCase()
                : "Medium";

              return (
                <button
                  key={task.id}
                  type="button"
                  onClick={() => onOpenTask?.(task)}
                  className="group flex w-full items-center gap-3 rounded-xl border border-transparent px-3 py-3 text-left transition-all hover:border-brand-100 hover:bg-brand-50/40 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-200"
                >
                  {/* Task */}
                  <div className="min-w-0 flex-1">
                    <p
                      className="truncate text-[13px] font-medium text-ink-900 transition-colors group-hover:text-brand-600"
                      title={task.title}
                    >
                      {task.title}
                    </p>

                    <div className="mt-1.5 flex items-center gap-2 text-[10px] text-ink-400">
                      {/* Assignee */}
                      <span className="inline-flex items-center gap-1.5">
                        <span className="flex h-5 w-5 items-center justify-center rounded-full bg-brand-50 text-[8px] font-semibold text-brand-600">
                          {task.assignee
                            ? task.assignee
                                .trim()
                                .split(/\s+/)
                                .map((part) => part[0])
                                .join("")
                                .slice(0, 2)
                                .toUpperCase()
                            : "—"}
                        </span>

                        <span className="max-w-[110px] truncate">
                          {task.assignee || "Unassigned"}
                        </span>
                      </span>

                      <span className="text-ink-200">•</span>

                      {/* Due date */}
                      <span className="whitespace-nowrap">
                        {formatDate(task.dueDate)}
                      </span>
                    </div>
                  </div>

                  {/* Priority */}
                  <span
                    className="inline-flex shrink-0 items-center gap-1.5 rounded-full border border-ink-900/5 px-2 py-1 text-[10px] font-medium text-ink-600"
                  >
                    <span
                      className={`h-1.5 w-1.5 rounded-full ${
                        priorityDot[task.priority] || "bg-ink-300"
                      }`}
                    />

                    {priority}
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* Footer */}
      {!loading && tasks.length > 0 && (
        <button
          type="button"
          onClick={onViewAll}
          className="mt-4 flex w-fit items-center gap-1 border-t border-ink-900/5 pt-3 text-sm font-medium text-brand-600 transition-colors hover:text-brand-700"
        >
          View all tasks →
        </button>
      )}
    </div>
  );
}