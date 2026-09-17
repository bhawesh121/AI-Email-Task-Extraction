// Thin client over the real aiassistant Spring Boot API.
//
// The frontend is deployed under /sla and nginx proxies:
//
//   /sla/api/**                  -> backend:8080/api/**
//   /sla/oauth2/**               -> backend:8080/oauth2/**
//   /sla/login/oauth2/code/**    -> backend:8080/login/oauth2/code/**
//
// Keep backend API paths in this file relative to /api.
//
// `credentials: "include"` is required because the backend is secured
// with Spring Security OAuth2 login. The browser must send the session
// cookie created after Microsoft authentication.

const BASE_PATH = "/sla";

/**
 * Build the browser-facing URL for a backend API request.
 *
 * Example:
 *
 *   /api/tasks
 *        ↓
 *   /sla/api/tasks
 */
function buildApiUrl(path, params) {
  const url = new URL(`${BASE_PATH}${path}`, window.location.origin);

  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, value);
      }
    });
  }

  return url;
}

/**
 * Execute an API request.
 */
async function request(path, options = {}, params) {
  const url = buildApiUrl(path, params);

  const res = await fetch(url.pathname + url.search, {
    credentials: "include",
    redirect: "manual",

    ...options,

    headers: {
      Accept: "application/json",
      ...(options.headers || {}),
    },
  });

  // Spring Security can redirect unauthenticated requests to Microsoft.
  // Because redirect handling is manual, treat that situation as an
  // unauthenticated API request rather than following the redirect.
  if (
    res.type === "opaqueredirect" ||
    res.status === 401 ||
    res.status === 403
  ) {
    const err = new Error("UNAUTHENTICATED");
    err.code = "UNAUTHENTICATED";
    throw err;
  }

  if (!res.ok) {
    throw new Error(`${path} failed with ${res.status}`);
  }

  return res.json();
}

/**
 * GET request helper.
 *
 * `options` is passed through to `fetch()`.
 *
 * This allows callers such as TasksPage to pass:
 *
 *   { signal: controller.signal }
 *
 * so in-flight requests can be cancelled when a newer search
 * or query replaces the previous one.
 */
async function get(path, params, options = {}) {
  return request(path, options, params);
}

/**
 * PATCH request helper.
 */
async function patch(path, params) {
  return request(
    path,
    {
      method: "PATCH",
    },
    params
  );
}

export const api = {
  // ==========================================================
  // Authentication
  // ==========================================================

  // GET /api/auth/me
  getCurrentUser: () => get("/api/auth/me"),

  // ==========================================================
  // Dashboard
  // ==========================================================

  // GET /api/dashboard/summary
  getDashboardSummary: () =>
    get("/api/dashboard/summary"),

  // ==========================================================
  // Tasks
  // ==========================================================

  // GET /api/tasks/summary
  getTaskSummary: (filters) =>
    get("/api/tasks/summary", filters),

  // GET /api/tasks/dashboard
  //
  // Server-side dashboard aggregates and small dashboard task
  // collections. This avoids downloading the complete task table.
  getTaskDashboard: (filters = {}, days = 7) =>
    get("/api/tasks/dashboard", {
      ...filters,
      days,
    }),

  // GET /api/tasks
  //
  // Legacy full-list endpoint kept for compatibility with any
  // remaining consumers. It should not be used by TasksPage or
  // DashboardPage.
  getAllTasks: () =>
    get("/api/tasks"),

  // GET /api/tasks/page
  //
  // Production task-list endpoint:
  // - server-side pagination
  // - server-side filtering
  // - server-side search
  // - server-side sorting
  //
  // `options` is passed through to fetch().
  //
  // Example:
  //
  //   api.getTasksPage(
  //     pageQuery,
  //     { signal: controller.signal }
  //   )
  //
  getTasksPage: (filters, options = {}) =>
    get("/api/tasks/page", filters, options),

  // GET /api/tasks/{id}
  //
  // Used for task detail/deep-link loading when the task isn't
  // currently present in the paginated result.
  getTask: (id) =>
    get(`/api/tasks/${id}`),

  // GET /api/tasks/filter
  //
  // Existing endpoint retained for compatibility.
  filterTasks: (filters) =>
    get("/api/tasks/filter", filters),

  // GET /api/tasks/filter-options
  getFilterOptions: () =>
    get("/api/tasks/filter-options"),

  // GET /api/tasks/overdue
  getOverdueTasks: () =>
    get("/api/tasks/overdue"),

  // PATCH /api/tasks/{id}/status?status=...
  updateTaskStatus: (id, status) =>
    patch(`/api/tasks/${id}/status`, {
      status,
    }),

  // PATCH /api/tasks/{id}/priority?priority=...
  updateTaskPriority: (id, priority) =>
    patch(`/api/tasks/${id}/priority`, {
      priority,
    }),

  // PATCH /api/tasks/{id}/assignee?assigneeEmail=...
  updateTaskAssignee: (id, assigneeEmail) =>
    patch(`/api/tasks/${id}/assignee`, {
      assigneeEmail,
    }),

  // ==========================================================
  // Emails
  // ==========================================================

  // GET /api/emails
  getEmails: () =>
    get("/api/emails"),

  // ==========================================================
  // Email synchronization
  // ==========================================================

  // GET /api/admin/email-sync/ingestion-timestamps?days=N
  getIngestionTimestamps: (days = 30) =>
    get(
      "/api/admin/email-sync/ingestion-timestamps",
      {
        days,
      }
    ),

  // ==========================================================
  // Mailboxes / sources
  // ==========================================================

  // GET /api/mailboxes
  getMailboxes: () =>
    get("/api/mailboxes"),

  // GET /api/email-sources
  getEmailSources: () =>
    get("/api/email-sources"),

  // ==========================================================
  // SLA
  // ==========================================================

  // GET /api/sla/summary?date=YYYY-MM-DD
  getSlaSummary: (date) =>
    get(
      "/api/sla/summary",
      date
        ? {
            date,
          }
        : undefined
    ),

  // GET /api/sla/trend?days=7
  getSlaTrend: (days) =>
    get("/api/sla/trend", {
      days,
    }),

  // GET /api/sla/breached?from=&to=&mailboxUserId=
  getBreachedEmails: (filters) =>
    get("/api/sla/breached", filters),
};

// Microsoft OAuth2 authorization endpoint.
//
// This is a browser navigation endpoint, not a JSON API request.
export const loginUrl =
  "/sla/oauth2/authorization/microsoft";