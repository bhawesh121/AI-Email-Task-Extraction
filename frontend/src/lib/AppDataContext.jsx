import React, {
  createContext,
  useContext,
  useCallback,
  useEffect,
  useState,
} from "react";

import { api } from "./api";

const AppDataContext = createContext(null);

export function AppDataProvider({ children }) {
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [unauthenticated, setUnauthenticated] = useState(false);
  const [error, setError] = useState(null);

  const [dashboardSummary, setDashboardSummary] = useState(null);
  const [taskSummary, setTaskSummary] = useState(null);
  const [taskDashboard, setTaskDashboard] = useState(null);
  const [emails, setEmails] = useState([]);
  const [ingestionTimestamps, setIngestionTimestamps] = useState([]);
  const [mailboxes, setMailboxes] = useState([]);
  const [emailSources, setEmailSources] = useState([]);

  const [filterOptions, setFilterOptions] = useState({
    assignees: [],
    sourceDomains: [],
    sourceTypes: [],
  });

  const [checks, setChecks] = useState([
    {
      label: "Dashboard Summary",
      detail: "/api/dashboard/summary",
      ok: false,
    },
    {
      label: "Task Service",
      detail: "/api/tasks/summary",
      ok: false,
    },
    {
      label: "Mailbox Connections",
      detail: "/api/mailboxes",
      ok: false,
    },
  ]);

  // Global dashboard filter — status/priority are applied server-side
  // to both task summary and dashboard task aggregates.
  const [filters, setFilters] = useState({
    status: "",
    priority: "",
  });

  const [trendDays, setTrendDays] = useState(7);

  /**
   * Loads the server-side dashboard task aggregates for the active filters.
   *
   * This replaces the old GET /api/tasks full-table download.
   */
  const loadTaskDashboard = useCallback(async (activeFilters, days) => {
    try {
      const data = await api.getTaskDashboard(
        activeFilters,
        days
      );

      setTaskDashboard(data);
      return data;
    } catch (e) {
      if (e?.code === "UNAUTHENTICATED") {
        setUnauthenticated(true);
      }

      throw e;
    }
  }, []);

  /**
   * Loads protected application data without ever downloading the complete
   * task table. Task-derived dashboard/workload information comes from the
   * dedicated server-side dashboard aggregate endpoint.
   */
  const loadAll = useCallback(
    async (activeFilters = {}, days = 7) => {
      setError(null);

      try {
        const results = await Promise.allSettled([
          api.getDashboardSummary(),
          api.getTaskSummary(activeFilters),
          api.getTaskDashboard(activeFilters, days),
          api.getEmails(),
          api.getIngestionTimestamps(30),
          api.getMailboxes(),
          api.getEmailSources(),
          api.getFilterOptions(),
        ]);

        const [
          summaryRes,
          taskSummaryRes,
          taskDashboardRes,
          emailsRes,
          ingestionRes,
          mailboxesRes,
          sourcesRes,
          optionsRes,
        ] = results;

        if (
          results.some(
            (result) =>
              result.status === "rejected" &&
              result.reason?.code === "UNAUTHENTICATED"
          )
        ) {
          setUnauthenticated(true);
          return;
        }

        if (summaryRes.status === "fulfilled") {
          setDashboardSummary(summaryRes.value);
        }

        if (taskSummaryRes.status === "fulfilled") {
          setTaskSummary(taskSummaryRes.value);
        }

        if (taskDashboardRes.status === "fulfilled") {
          setTaskDashboard(taskDashboardRes.value);
        }

        if (emailsRes.status === "fulfilled") {
          setEmails(emailsRes.value);
        }

        if (ingestionRes.status === "fulfilled") {
          setIngestionTimestamps(ingestionRes.value);
        }

        if (mailboxesRes.status === "fulfilled") {
          setMailboxes(mailboxesRes.value);
        }

        if (sourcesRes.status === "fulfilled") {
          setEmailSources(sourcesRes.value);
        }

        if (optionsRes.status === "fulfilled") {
          setFilterOptions(optionsRes.value);
        }

        setChecks([
          {
            label: "Dashboard Summary",
            detail: "/api/dashboard/summary",
            ok: summaryRes.status === "fulfilled",
          },
          {
            label: "Task Service",
            detail: "/api/tasks/summary",
            ok: taskSummaryRes.status === "fulfilled",
          },
          {
            label: "Mailbox Connections",
            detail: "/api/mailboxes",
            ok: mailboxesRes.status === "fulfilled",
          },
        ]);

        if (results.every((result) => result.status === "rejected")) {
          setError(
            "We couldn't load your workspace. Please try again in a moment. If the issue persists, contact your administrator."
          );
        }
      } finally {
        setRefreshing(false);
      }
    },
    []
  );

  /**
   * Single authentication gate for the entire application.
   */
  const initialize = useCallback(async () => {
    setLoading(true);
    setRefreshing(false);
    setError(null);

    try {
      await api.getCurrentUser();

      setUnauthenticated(false);

      await loadAll({ status: "", priority: "" }, 7);
    } catch (e) {
      if (e?.code === "UNAUTHENTICATED") {
        setUnauthenticated(true);
        return;
      }

      setError(
        "We couldn't verify your session. Please try again in a moment."
      );
    } finally {
      setLoading(false);
    }
  }, [loadAll]);

  useEffect(() => {
    initialize();
  }, [initialize]);

  useEffect(() => {
    const handlePageShow = (event) => {
      if (!event.persisted) {
        return;
      }

      initialize();
    };

    window.addEventListener("pageshow", handlePageShow);

    return () => {
      window.removeEventListener("pageshow", handlePageShow);
    };
  }, [initialize]);

  const isFirstFilterRender = React.useRef(true);

  /**
   * Dashboard filters now re-query BOTH the task summary and the task
   * dashboard aggregates on the server. No full task collection is loaded.
   */
  useEffect(() => {
    if (isFirstFilterRender.current) {
      isFirstFilterRender.current = false;
      return;
    }

    if (unauthenticated) {
      return;
    }

    Promise.all([
      api.getTaskSummary(filters),
      api.getTaskDashboard(filters, trendDays),
    ])
      .then(([summary, dashboard]) => {
        setTaskSummary(summary);
        setTaskDashboard(dashboard);
      })
      .catch((e) => {
        if (e?.code === "UNAUTHENTICATED") {
          setUnauthenticated(true);
        }
      });
  }, [filters, trendDays, unauthenticated]);

  const refresh = useCallback(() => {
    if (unauthenticated) {
      initialize();
      return;
    }

    setRefreshing(true);
    loadAll(filters, trendDays);
  }, [initialize, loadAll, filters, trendDays, unauthenticated]);

  // ==========================================================
  // Optimistic task mutations
  // ==========================================================

  const updateTaskStatus = useCallback(
    async (id, status) => {
      try {
        const updated = await api.updateTaskStatus(id, status);

        // Refresh the small server-side dashboard projection so Dashboard
        // and workload stay consistent with PostgreSQL.
        void loadTaskDashboard(filters, trendDays).catch(() => {});

        return {
          ok: true,
          task: updated,
        };
      } catch (e) {
        if (e?.code === "UNAUTHENTICATED") {
          setUnauthenticated(true);
        }

        return {
          ok: false,
          error: e.message,
        };
      }
    },
    [loadTaskDashboard, filters, trendDays]
  );

  const updateTaskPriority = useCallback(
    async (id, priority) => {
      try {
        const updated = await api.updateTaskPriority(
          id,
          priority
        );

        void loadTaskDashboard(filters, trendDays).catch(() => {});

        return {
          ok: true,
          task: updated,
        };
      } catch (e) {
        if (e?.code === "UNAUTHENTICATED") {
          setUnauthenticated(true);
        }

        return {
          ok: false,
          error: e.message,
        };
      }
    },
    [loadTaskDashboard, filters, trendDays]
  );

  const value = {
    loading,
    refreshing,
    unauthenticated,
    error,

    dashboardSummary,
    taskSummary,
    taskDashboard,
    emails,
    ingestionTimestamps,
    mailboxes,
    emailSources,
    filterOptions,
    checks,

    filters,
    setFilters,

    trendDays,
    setTrendDays,

    refresh,

    updateTaskStatus,
    updateTaskPriority,
  };

  return (
    <AppDataContext.Provider value={value}>
      {children}
    </AppDataContext.Provider>
  );
}

export function useAppData() {
  const ctx = useContext(AppDataContext);

  if (!ctx) {
    throw new Error(
      "useAppData must be used within AppDataProvider"
    );
  }

  return ctx;
}
