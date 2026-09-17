// Everything here derives dashboard shapes from the raw arrays the API
// actually returns (TaskDto[], EmailDto[]) — there is no dedicated
// aggregation endpoint yet, so it's computed client-side.

export function tasksByAssignee(tasks, topN = 5) {
  const counts = new Map();
  for (const t of tasks) {
    const name = t.assignee || "Unassigned";
    counts.set(name, (counts.get(name) || 0) + 1);
  }
  return [...counts.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, topN);
}

export function upcomingDeadlines(tasks, topN = 5) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  return tasks
    .filter((t) => t.dueDate && t.status !== "COMPLETED" && t.status !== "ARCHIVED")
    .filter((t) => new Date(t.dueDate) >= today)
    .sort((a, b) => new Date(a.dueDate) - new Date(b.dueDate))
    .slice(0, topN);
}

export function statusBreakdown(summary) {
  if (!summary) return [];
  return [
    { key: "NEW", label: "New", value: summary.newTasks, color: "#5b48e8" },
    { key: "IN_PROGRESS", label: "In Progress", value: summary.inProgress, color: "#22c55e" },
    { key: "COMPLETED", label: "Completed", value: summary.completed, color: "#a4a8ba" },
    { key: "BLOCKED", label: "Blocked", value: summary.blocked, color: "#ef4444" },
  ].filter((s) => s.value > 0);
}

// Buckets items with a date-like field into the last N days (inclusive of today),
// returning one point per day so charts have a stable, gap-free x-axis.
function localDateKey(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function lastNDaysCounts(items, dateField, days = 7) {
  const buckets = [];
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today);
    d.setDate(d.getDate() - i);
    buckets.push({
      key: localDateKey(d),
      label: d.toLocaleDateString("en-US", { month: "short", day: "numeric" }),
      count: 0,
    });
  }

  const byKey = new Map(buckets.map((b) => [b.key, b]));
  for (const item of items) {
    const raw = item[dateField];
    if (!raw) continue;
    const key = localDateKey(new Date(raw));
    const bucket = byKey.get(key);
    if (bucket) bucket.count += 1;
  }

  return buckets;
}

export function uniqueTaskSources(tasks) {
  // "Unique" tasks = distinct sourceEmailId, falling back to task id
  // for manually-created tasks with no source email.
  const seen = new Set();
  return tasks.filter((t) => {
    const key = t.sourceEmailId || t.id;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export const priorityColor = {
  CRITICAL: "text-red-600 bg-red-50",
  HIGH: "text-red-600 bg-red-50",
  MEDIUM: "text-amber-600 bg-amber-50",
  LOW: "text-blue-600 bg-blue-50",
};

export const priorityDot = {
  CRITICAL: "bg-red-500",
  HIGH: "bg-red-500",
  MEDIUM: "bg-amber-500",
  LOW: "bg-blue-500",
};

export function formatDate(dateStr) {
  if (!dateStr) return "—";
  return new Date(dateStr).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

// §16 Employee Workload/Ownership — per-assignee breakdown of open,
// overdue, and high-priority load. Built entirely from TaskDto fields
// already returned by /api/tasks; no new backend work required.
export function employeeWorkload(tasks) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const byName = new Map();

  for (const t of tasks) {
    const name = t.assignee || "Unassigned";
    if (!byName.has(name)) byName.set(name, { name, open: 0, overdue: 0, highPriority: 0, total: 0 });
    const row = byName.get(name);
    row.total += 1;
    const isOpen = t.status !== "COMPLETED" && t.status !== "ARCHIVED";
    if (isOpen) row.open += 1;
    if (isOpen && t.dueDate && new Date(t.dueDate) < today) row.overdue += 1;
    if (isOpen && (t.priority === "HIGH" || t.priority === "CRITICAL")) row.highPriority += 1;
  }

  return [...byName.values()].sort((a, b) => b.open - a.open);
}

// §4 Important Relationships — top external senders by email volume.
// Uses EmailDto.sourceType to exclude internal mail; no new fields needed.
// §4 Important Relationships — top external senders by email volume.
// Your backend classifies every sender's domain into one of four buckets
// (see classifySource() in GraphEmailService.java): INTERNAL, EXTERNAL,
// GMAIL, or MICROSOFT. Only INTERNAL means "one of ours" — GMAIL and
// MICROSOFT are personal/consumer providers, which are still genuinely
// external senders, so all three non-INTERNAL buckets count here. Records
// with no sourceType at all are skipped rather than assumed external,
// since we can't actually tell.
export function topExternalContacts(emails, topN = 5) {
  const byEmail = new Map();
  for (const e of emails) {
    if (!e.sourceType || e.sourceType === "INTERNAL") continue;
    const key = e.senderEmail || e.senderDomain || "Unknown";
    if (!byEmail.has(key)) {
      byEmail.set(key, { email: e.senderEmail, name: e.senderName || e.senderEmail, domain: e.senderDomain, count: 0 });
    }
    byEmail.get(key).count += 1;
  }
  return [...byEmail.values()].sort((a, b) => b.count - a.count).slice(0, topN);
}

// §14 "What Needs My Attention" — a single prioritized queue combining
// overdue tasks and open high/critical-priority tasks. Sorted so the most
// overdue items surface first, then upcoming high-priority work.
export function attentionQueue(tasks, topN = 6) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  const isOpen = (t) => t.status !== "COMPLETED" && t.status !== "ARCHIVED";
  const daysOverdue = (t) => (t.dueDate ? Math.floor((today - new Date(t.dueDate)) / 86400000) : -Infinity);

  const overdue = tasks.filter((t) => isOpen(t) && t.dueDate && new Date(t.dueDate) < today);
  const highPriorityOpen = tasks.filter(
    (t) => isOpen(t) && (t.priority === "HIGH" || t.priority === "CRITICAL") && !(t.dueDate && new Date(t.dueDate) < today)
  );

  const combined = [
    ...overdue.map((t) => ({ ...t, _reason: "overdue" })),
    ...highPriorityOpen.map((t) => ({ ...t, _reason: "priority" })),
  ].sort((a, b) => {
    if (a._reason !== b._reason) return a._reason === "overdue" ? -1 : 1;
    if (a._reason === "overdue") return daysOverdue(b) - daysOverdue(a);
    const da = a.dueDate ? new Date(a.dueDate).getTime() : Infinity;
    const db = b.dueDate ? new Date(b.dueDate).getTime() : Infinity;
    return da - db;
  });

  return {
    items: combined.slice(0, topN),
    overdueCount: overdue.length,
    highPriorityCount: highPriorityOpen.length,
  };
}
