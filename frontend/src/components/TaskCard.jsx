import React from "react";
import { formatDate, priorityDot } from "../lib/derive";
import { ChevronRightIcon, SparkleIcon } from "./Icons";

const STATUS_STYLE = {
  NEW: "bg-blue-50 text-blue-700",
  IN_PROGRESS: "bg-amber-50 text-amber-700",
  COMPLETED: "bg-emerald-50 text-emerald-700",
  BLOCKED: "bg-red-50 text-red-600",
  ARCHIVED: "bg-ink-900/5 text-ink-500",
};

function initials(name) {
  if (!name) return "?";
  return name.split(" ").map((p) => p[0]).join("").slice(0, 2).toUpperCase();
}

export default function TaskCard({ task, onOpen }) {
  const overdue =
    task.dueDate && new Date(task.dueDate) < new Date() && task.status !== "COMPLETED" && task.status !== "ARCHIVED";

  return (
    <button
      onClick={() => onOpen(task)}
      className="group flex w-full flex-col gap-3 rounded-xl border border-ink-900/5 bg-white p-4 text-left shadow-card transition-all hover:-translate-y-0.5 hover:border-brand-200 hover:shadow-md"
    >
      <div className="flex items-start justify-between gap-3">
        {/* Full title, wraps up to 2 lines instead of being sliced off */}
        <h3 className="line-clamp-2 text-[15px] font-semibold leading-snug text-ink-900">
          {task.title}
        </h3>
        <ChevronRightIcon
          width={16}
          height={16}
          className="mt-1 shrink-0 text-ink-300 transition-transform group-hover:translate-x-0.5 group-hover:text-brand-500"
        />
      </div>

      {task.description && (
        <p className="line-clamp-2 text-sm leading-relaxed text-ink-500">{task.description}</p>
      )}

      {task.sourceSender && (
        <p className="flex items-center gap-1.5 truncate text-xs text-ink-300">
          <SparkleIcon width={12} height={12} />
          from: {task.sourceSender}
          {task.sourceSubject && <span className="text-ink-300/70"> — {task.sourceSubject}</span>}
        </p>
      )}

      <div className="mt-1 flex flex-wrap items-center gap-2">
        <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[task.status] || STATUS_STYLE.ARCHIVED}`}>
          {task.status?.replace("_", " ")}
        </span>
        <span className="flex items-center gap-1 rounded-full bg-ink-900/[0.04] px-2 py-0.5 text-xs font-medium text-ink-700">
          <span className={`h-1.5 w-1.5 rounded-full ${priorityDot[task.priority] || "bg-ink-300"}`} />
          {task.priority?.charAt(0) + task.priority?.slice(1).toLowerCase()}
        </span>
        {overdue && (
          <span className="rounded-full bg-red-50 px-2 py-0.5 text-xs font-semibold text-red-600">
            Overdue
          </span>
        )}
      </div>

      <div className="mt-1 flex items-center justify-between border-t border-ink-900/5 pt-3">
        <div className="flex items-center gap-2">
          <div className="flex h-6 w-6 items-center justify-center rounded-full bg-brand-100 text-[10px] font-semibold text-brand-700">
            {initials(task.assignee)}
          </div>
          <span className="text-xs font-medium text-ink-700">{task.assignee || "Unassigned"}</span>
        </div>
        <span className="text-xs text-ink-300">{formatDate(task.dueDate)}</span>
      </div>
    </button>
  );
}
