import React, { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import TopBar from "../components/TopBar";
import StatCard from "../components/StatCard";
import TaskStatusCard from "../components/TaskStatusCard";
import TasksByAssigneeCard from "../components/TasksByAssigneeCard";
import UpcomingDeadlinesCard from "../components/UpcomingDeadlinesCard";
import IngestionChartCard from "../components/IngestionChartCard";
import EmailSlaCard from "../components/EmailSlaCard";
import RecentActivityCard from "../components/RecentActivityCard";
import AttentionQueueCard from "../components/AttentionQueueCard";
import TopContactsCard from "../components/TopContactsCard";
import {
  MailIcon,
  DocIcon,
  ClipboardIcon,
  ClockIcon,
  FlagIcon,
  StarIcon,
} from "../components/Icons";
import { useAppData } from "../lib/AppDataContext";
import { lastNDaysCounts, topExternalContacts } from "../lib/derive";

export default function DashboardPage() {
  const navigate = useNavigate();

  const {
    loading,
    error,
    dashboardSummary,
    taskSummary,
    taskDashboard,
    emails,
    ingestionTimestamps,
    trendDays,
  } = useAppData();

  const emailsPerDay = useMemo(
    () =>
      lastNDaysCounts(
        (ingestionTimestamps ?? []).map((ts) => ({ createdAt: ts })),
        "createdAt",
        trendDays
      ),
    [ingestionTimestamps, trendDays]
  );

  const taskTrend = useMemo(() => {
    const byDate = new Map(
      (taskDashboard?.taskTrend ?? []).map((point) => [point.date, point.count])
    );

    return Array.from({ length: trendDays }, (_, index) => {
      const d = new Date();
      d.setHours(0, 0, 0, 0);
      d.setDate(d.getDate() - (trendDays - 1 - index));

      const key = [
        d.getFullYear(),
        String(d.getMonth() + 1).padStart(2, "0"),
        String(d.getDate()).padStart(2, "0"),
      ].join("-");

      return {
        key,
        label: d.toLocaleDateString("en-US", {
          month: "short",
          day: "numeric",
        }),
        count: Number(byDate.get(key) ?? 0),
      };
    });
  }, [taskDashboard?.taskTrend, trendDays]);

  const topContacts = topExternalContacts(emails);

  const attentionItems = taskDashboard?.attentionItems ?? [];
  const upcomingDeadlines = taskDashboard?.upcomingDeadlines ?? [];
  const recentActivity = taskDashboard?.recentActivity ?? [];
  const tasksByAssignee = taskDashboard?.tasksByAssignee ?? [];

  const statCards = [
    {
      label: "Emails Processed",
      value: dashboardSummary?.totalEmails ?? emails.length,
      icon: <MailIcon />,
      iconBg: "#eef0ff",
    },
    {
      label: "Actionable Emails",
      value: taskDashboard?.actionableEmails ?? 0,
      icon: <DocIcon />,
      iconBg: "#e6f6fe",
    },
    {
      label: "Tasks Generated",
      value: taskSummary?.total ?? 0,
      icon: <ClipboardIcon />,
      iconBg: "#f3ebff",
    },
    {
      label: "Open Tasks",
      value:
        (taskSummary?.newTasks ?? 0) +
        (taskSummary?.inProgress ?? 0),
      icon: <ClockIcon />,
      iconBg: "#fff1e6",
    },
    {
      label: "Overdue Tasks",
      value: taskSummary?.overdue ?? 0,
      icon: <FlagIcon />,
      iconBg: "#fdeaea",
    },
    {
      label: "High Priority Tasks",
      value: taskSummary?.highPriority ?? 0,
      icon: <StarIcon />,
      iconBg: "#fef7e0",
    },
  ];

  return (
    <div className="space-y-5 p-6">
      <TopBar
        title="Dashboard"
        subtitle="Overview of email processing, tasks, and workload"
      />

      {error && (
        <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">
          {error}
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-6">
        {statCards.map((c) => (
          <StatCard
            key={c.label}
            {...c}
            loading={loading}
          />
        ))}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <AttentionQueueCard
            overdueCount={taskDashboard?.overdueCount ?? 0}
            highPriorityCount={taskDashboard?.highPriorityCount ?? 0}
            items={attentionItems}
            loading={loading || !taskDashboard}
            onOpenTask={(t) =>
              navigate(`/tasks?taskId=${t.id}`)
            }
            onViewOverdue={() =>
              navigate("/tasks?attention=overdue")
            }
            onViewHighPriority={() =>
              navigate("/tasks?attention=priority")
            }
          />
        </div>

        <TopContactsCard
          data={topContacts}
          loading={loading}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <TaskStatusCard
          breakdown={[
            { key: "NEW", label: "New", value: taskSummary?.newTasks ?? 0, color: "#5b48e8" },
            { key: "IN_PROGRESS", label: "In Progress", value: taskSummary?.inProgress ?? 0, color: "#22c55e" },
            { key: "COMPLETED", label: "Completed", value: taskSummary?.completed ?? 0, color: "#a4a8ba" },
            { key: "BLOCKED", label: "Blocked", value: taskSummary?.blocked ?? 0, color: "#ef4444" },
          ].filter((s) => s.value > 0)}
          total={taskSummary?.total ?? 0}
          loading={loading}
        />

        <TasksByAssigneeCard
          data={tasksByAssignee}
          loading={loading || !taskDashboard}
          onViewAll={() => navigate("/tasks")}
        />

        <UpcomingDeadlinesCard
          tasks={upcomingDeadlines}
          loading={loading || !taskDashboard}
          onOpenTask={(t) => navigate(`/tasks?taskId=${t.id}`)}
          onViewAll={() => navigate("/tasks")}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
        <IngestionChartCard
          emailSeries={emailsPerDay}
          taskSeries={taskTrend}
          loading={loading || !taskDashboard}
          days={trendDays}
        />

        <EmailSlaCard />

        <RecentActivityCard
          tasks={recentActivity}
          loading={loading || !taskDashboard}
          onViewAll={() => navigate("/tasks")}
        />
      </div>
    </div>
  );
}
