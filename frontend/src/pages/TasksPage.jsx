import React, { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api } from "../lib/api";
import { useAppData } from "../lib/AppDataContext";
import TaskCard from "../components/TaskCard";
import TaskDetailDrawer from "../components/TaskDetailDrawer";
import EmployeeWorkloadCard from "../components/EmployeeWorkloadCard";

const STATUS_OPTIONS = ["NEW", "IN_PROGRESS", "COMPLETED", "BLOCKED", "ARCHIVED"];
const PRIORITY_OPTIONS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];
const PAGE_SIZE = 12;
const SEARCH_MIN_CHARS = 2;
const SEARCH_DEBOUNCE_MS = 200;
const SORT_OPTIONS = [
  { value: "due-asc", label: "Due date (soonest)" },
  { value: "due-desc", label: "Due date (latest)" },
  { value: "received-desc", label: "Date received (newest)" },
  { value: "received-asc", label: "Date received (oldest)" },
  { value: "priority", label: "Priority (highest first)" },
  { value: "recent", label: "Recently updated" },
];

export default function TasksPage() {
  const {
    filterOptions,
    updateTaskStatus,
    updateTaskPriority,
    taskDashboard,
    loading: contextLoading,
    realtimeEvent,
  } = useAppData();

  const [searchParams, setSearchParams] = useSearchParams();
  const attention = searchParams.get("attention");
  const deepLinkTaskId = searchParams.get("taskId");

  const [serverFilters, setServerFilters] = useState({
    status: "",
    priority: "",
    assigneeEmail: "",
    sourceDomain: "",
    sourceType: "",
  });

  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [sort, setSort] = useState("received-desc");
  const [page, setPage] = useState(1);
  const [tasks, setTasks] = useState([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [savingId, setSavingId] = useState(null);
  const [toast, setToast] = useState(null);
  const [openTask, setOpenTask] = useState(null);
  const [openingTaskId, setOpeningTaskId] = useState(null);
  const [reloadVersion, setReloadVersion] = useState(0);

  const hasFilters = Object.values(serverFilters).some(Boolean);

  useEffect(() => {
    const trimmed = search.trim();

    const timer = setTimeout(() => {
      // Empty input clears the server-side search immediately.
      // For one-character input, keep the previous results until the
      // user provides a meaningful two-character query. This prevents
      // expensive broad queries for single-character prefixes.
      if (trimmed.length === 0 || trimmed.length >= SEARCH_MIN_CHARS) {
        setDebouncedSearch(trimmed);
      }
    }, SEARCH_DEBOUNCE_MS);

    return () => clearTimeout(timer);
  }, [search]);

  const pageQuery = useMemo(
    () => ({
      page: page - 1,
      size: PAGE_SIZE,
      status: serverFilters.status || undefined,
      priority: serverFilters.priority || undefined,
      assigneeEmail: serverFilters.assigneeEmail || undefined,
      sourceDomain: serverFilters.sourceDomain || undefined,
      sourceType: serverFilters.sourceType || undefined,
      search: debouncedSearch || undefined,
      attention: attention || undefined,
      sort,
    }),
    [
      page,
      serverFilters,
      debouncedSearch,
      attention,
      sort,
    ]
  );

  const pageQueryKey = useMemo(
    () => JSON.stringify({
      status: serverFilters.status,
      priority: serverFilters.priority,
      assigneeEmail: serverFilters.assigneeEmail,
      sourceDomain: serverFilters.sourceDomain,
      sourceType: serverFilters.sourceType,
      search: debouncedSearch,
      attention,
      sort,
    }),
    [
      serverFilters,
      debouncedSearch,
      attention,
      sort,
    ]
  );

  const previousPageQueryKey = useRef(pageQueryKey);

  useEffect(() => {
    // UI pages are 1-based; the backend API is 0-based.
    // When a filter/search/sort/attention query changes, restart from page 1.
    // A normal page change (Prev/Next) keeps the requested page.
    const queryChanged = previousPageQueryKey.current !== pageQueryKey;

    if (queryChanged) {
      previousPageQueryKey.current = pageQueryKey;

      if (page !== 1) {
        setPage(1);
        return;
      }
    }

    const controller = new AbortController();
    let active = true;

    setLoading(true);

    api
      .getTasksPage(pageQuery, { signal: controller.signal })
      .then((response) => {
        if (!active) return;

        const items = Array.isArray(response?.items)
          ? response.items
          : [];

        setTasks(items);
        setTotalElements(Number(response?.totalElements ?? 0));
        setTotalPages(Math.max(1, Number(response?.totalPages ?? 1)));
      })
      .catch((error) => {
        if (!active || error?.name === "AbortError") return;

        if (error?.code === "UNAUTHENTICATED") {
          setToast({
            type: "error",
            text: "Your session has expired. Please sign in again.",
          });
        } else {
          setToast({
            type: "error",
            text: "Couldn't load tasks.",
          });
        }

        setTasks([]);
        setTotalElements(0);
        setTotalPages(1);
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [
    pageQuery,
    pageQueryKey,
    page,
    reloadVersion,
    realtimeEvent?.id,
  ]);

  useEffect(() => {
    const type = realtimeEvent?.type;
    const entityId = realtimeEvent?.entityId;

    const taskEvent =
      type === "TASK_CREATED" ||
      type === "TASK_STATUS_CHANGED" ||
      type === "TASK_PRIORITY_CHANGED" ||
      type === "TASK_ASSIGNEE_CHANGED" ||
      type === "TASK_UPDATED";

    if (!taskEvent || !openTask?.id || entityId !== openTask.id) {
      return;
    }

    api
      .getTask(openTask.id)
      .then(setOpenTask)
      .catch(() => {});
  }, [realtimeEvent?.id, openTask?.id]);

  useEffect(() => {
    if (!toast) return;

    const t = setTimeout(() => setToast(null), 3000);

    return () => clearTimeout(t);
  }, [toast]);

  // Deduplicate assignee options by normalized email.
  const assigneeOptions = useMemo(() => {
    const byEmail = new Map();

    for (const assignee of filterOptions.assignees ?? []) {
      const email = String(assignee?.email ?? "")
        .trim()
        .toLowerCase();

      if (!email || byEmail.has(email)) {
        continue;
      }

      byEmail.set(email, {
        ...assignee,
        email,
      });
    }

    return Array.from(byEmail.values());
  }, [filterOptions.assignees]);

  async function handleOpenTask(taskId) {
    setOpeningTaskId(taskId);

    try {
      const task = await api.getTask(taskId);
      setOpenTask(task);
    } catch (error) {
      setToast({
        type: "error",
        text:
          error?.code === "UNAUTHENTICATED"
            ? "Your session has expired. Please sign in again."
            : "Couldn\'t open the requested task.",
      });
    } finally {
      setOpeningTaskId(null);
    }
  }

  // Deep link: /tasks?taskId=... always loads the full detail DTO directly.
  // The paginated list only contains the lightweight TaskListItemDto.
  useEffect(() => {
    if (!deepLinkTaskId) return;

    let active = true;

    setOpeningTaskId(deepLinkTaskId);

    api
      .getTask(deepLinkTaskId)
      .then((task) => {
        if (!active) return;

        setOpenTask(task);

        const nextParams = new URLSearchParams(searchParams);
        nextParams.delete("taskId");
        setSearchParams(nextParams, { replace: true });
      })
      .catch((error) => {
        if (!active) return;

        setToast({
          type: "error",
          text:
            error?.code === "UNAUTHENTICATED"
              ? "Your session has expired. Please sign in again."
              : "Couldn\'t open the requested task.",
        });
      })
      .finally(() => {
        if (active) {
          setOpeningTaskId(null);
        }
      });

    return () => {
      active = false;
    };
  }, [
    deepLinkTaskId,
    searchParams,
    setSearchParams,
  ]);

  async function handleStatusChange(task, status) {
    setSavingId(task.id);

    const res = await updateTaskStatus(task.id, status);

    if (res.ok) {
      setTasks((currentTasks) =>
        currentTasks.map((currentTask) =>
          currentTask.id === task.id
            ? { ...currentTask, status }
            : currentTask
        )
      );

      setOpenTask((currentTask) =>
        currentTask && currentTask.id === task.id
          ? { ...currentTask, status }
          : currentTask
      );

      setReloadVersion((version) => version + 1);
    }

    setSavingId(null);

    setToast(
      res.ok
        ? {
            type: "success",
            text: "Status updated.",
          }
        : {
            type: "error",
            text: "Couldn't update status.",
          }
    );
  }

  async function handlePriorityChange(task, priority) {
    setSavingId(task.id);

    const res = await updateTaskPriority(task.id, priority);

    if (res.ok) {
      setTasks((currentTasks) =>
        currentTasks.map((currentTask) =>
          currentTask.id === task.id
            ? { ...currentTask, priority }
            : currentTask
        )
      );

      setOpenTask((currentTask) =>
        currentTask && currentTask.id === task.id
          ? { ...currentTask, priority }
          : currentTask
      );

      setReloadVersion((version) => version + 1);
    }

    setSavingId(null);

    setToast(
      res.ok
        ? {
            type: "success",
            text: "Priority updated.",
          }
        : {
            type: "error",
            text: "Couldn't update priority.",
          }
    );
  }

  return (
    <div className="space-y-5 p-6">
      <div>
        <h1 className="text-2xl font-bold text-ink-900">
          Tasks & Workload
        </h1>

        <p className="text-sm text-ink-500">
          {totalElements.toLocaleString()} task
          {totalElements === 1 ? "" : "s"}
          {hasFilters || debouncedSearch || attention
            ? " matching filters"
            : " total"}{" "}
          — click any card for the full picture
        </p>
      </div>

      {attention && (
        <div className="flex items-center justify-between rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-800">
          <span>
            Showing only {attention === "overdue" ? "overdue" : "high-priority"} tasks
          </span>

          <button
            onClick={() => {
              const nextParams = new URLSearchParams(searchParams);
              nextParams.delete("attention");
              setSearchParams(nextParams, {
                replace: true,
              });
            }}
            className="font-medium underline decoration-amber-400 hover:text-amber-900"
          >
            Clear
          </button>
        </div>
      )}

      <EmployeeWorkloadCard
        data={taskDashboard?.employeeWorkload ?? []}
        loading={contextLoading || !taskDashboard}
      />

      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-ink-900/5 bg-white p-3 shadow-card">
        <div className="min-w-[240px] flex-1">
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search tasks…"
            className="w-full rounded-lg border border-ink-900/10 px-3 py-2 text-sm"
            aria-describedby="task-search-help"
          />
          {search.trim().length === 1 && (
            <div
              id="task-search-help"
              className="mt-1 text-xs text-ink-400"
            >
              Type at least 2 characters to search.
            </div>
          )}
        </div>

        <select
          value={serverFilters.status}
          onChange={(e) =>
            setServerFilters((filters) => ({
              ...filters,
              status: e.target.value,
            }))
          }
          className="rounded-lg border border-ink-900/10 px-2 py-2 text-sm"
        >
          <option value="">All statuses</option>

          {STATUS_OPTIONS.map((status) => (
            <option key={status} value={status}>
              {status.replace("_", " ")}
            </option>
          ))}
        </select>

        <select
          value={serverFilters.priority}
          onChange={(e) =>
            setServerFilters((filters) => ({
              ...filters,
              priority: e.target.value,
            }))
          }
          className="rounded-lg border border-ink-900/10 px-2 py-2 text-sm"
        >
          <option value="">All priorities</option>

          {PRIORITY_OPTIONS.map((priority) => (
            <option key={priority} value={priority}>
              {priority.charAt(0) + priority.slice(1).toLowerCase()}
            </option>
          ))}
        </select>

        <select
          value={serverFilters.assigneeEmail}
          onChange={(e) =>
            setServerFilters((filters) => ({
              ...filters,
              assigneeEmail: e.target.value,
            }))
          }
          className="rounded-lg border border-ink-900/10 px-2 py-2 text-sm"
        >
          <option value="">All assignees</option>

          {assigneeOptions.map((assignee) => (
            <option key={assignee.email} value={assignee.email}>
              {assignee.name}
            </option>
          ))}
        </select>

        <select
          value={serverFilters.sourceDomain}
          onChange={(e) =>
            setServerFilters((filters) => ({
              ...filters,
              sourceDomain: e.target.value,
            }))
          }
          className="rounded-lg border border-ink-900/10 px-2 py-2 text-sm"
        >
          <option value="">All domains</option>

          {filterOptions.sourceDomains?.map((domain) => (
            <option key={domain} value={domain}>
              {domain}
            </option>
          ))}
        </select>

        <select
          value={sort}
          onChange={(e) => setSort(e.target.value)}
          className="rounded-lg border border-ink-900/10 px-2 py-2 text-sm"
        >
          {SORT_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>

        {hasFilters && (
          <button
            onClick={() =>
              setServerFilters({
                status: "",
                priority: "",
                assigneeEmail: "",
                sourceDomain: "",
                sourceType: "",
              })
            }
            className="text-sm font-medium text-brand-600 hover:text-brand-700"
          >
            Clear
          </button>
        )}
      </div>

      {/* Card grid */}
      {loading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {[...Array(9)].map((_, index) => (
            <div
              key={index}
              className="h-44 animate-pulse rounded-xl bg-white shadow-card"
            />
          ))}
        </div>
      ) : tasks.length === 0 ? (
        <div className="rounded-2xl border border-ink-900/5 bg-white p-14 text-center shadow-card">
          <p className="text-sm text-ink-300">
            No tasks match these filters.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {tasks.map((task) => (
            <TaskCard
              key={task.id}
              task={task}
              onOpen={(task) => handleOpenTask(task.id)}
            />
          ))}
        </div>
      )}

      {!loading && totalPages > 1 && (
        <div className="flex items-center justify-between px-1 text-sm text-ink-500">
          <span>
            Page {page} of {totalPages}
          </span>

          <div className="flex gap-2">
            <button
              disabled={page <= 1}
              onClick={() => setPage((currentPage) => currentPage - 1)}
              className="rounded-md border border-ink-900/10 bg-white px-2.5 py-1 disabled:opacity-40"
            >
              Prev
            </button>

            <button
              disabled={page >= totalPages}
              onClick={() => setPage((currentPage) => currentPage + 1)}
              className="rounded-md border border-ink-900/10 bg-white px-2.5 py-1 disabled:opacity-40"
            >
              Next
            </button>
          </div>
        </div>
      )}

      <TaskDetailDrawer
        task={openTask}
        onClose={() => setOpenTask(null)}
        onStatusChange={handleStatusChange}
        onPriorityChange={handlePriorityChange}
        saving={openTask ? savingId === openTask.id : false}
      />

      {toast && (
        <div
          className={`fixed bottom-6 right-6 z-50 rounded-lg px-4 py-2.5 text-sm font-medium text-white shadow-lg ${
            toast.type === "success" ? "bg-emerald-600" : "bg-red-600"
          }`}
        >
          {toast.text}
        </div>
      )}
    </div>
  );
}