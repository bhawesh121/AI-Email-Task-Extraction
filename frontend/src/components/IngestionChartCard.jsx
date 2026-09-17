import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";

const SERIES = {
  emails: {
    key: "Emails Received",
    label: "Emails Received",
    color: "#5b48e8",
  },
  tasks: {
    key: "Unique Tasks",
    label: "Unique Tasks",
    color: "#22c55e",
  },
};

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

function EnterpriseTooltip({ active, payload, label }) {
  if (!active || !payload?.length) {
    return null;
  }

  return (
    <div className="min-w-[180px] rounded-lg border border-ink-900/10 bg-white px-3 py-2.5 shadow-lg">
      <p className="mb-2 text-[11px] font-medium text-ink-400">
        {label}
      </p>

      <div className="space-y-1.5">
        {payload.map((item) => (
          <div
            key={item.dataKey}
            className="flex items-center justify-between gap-5"
          >
            <div className="flex items-center gap-2">
              <span
                className="h-2 w-2 rounded-full"
                style={{ backgroundColor: item.color }}
              />

              <span className="text-xs text-ink-500">
                {item.name}
              </span>
            </div>

            <span className="text-xs font-semibold tabular-nums text-ink-900">
              {formatNumber(item.value)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

function TaskDot(props) {
  const {
    cx,
    cy,
    stroke,
    fill = "#ffffff",
    r = 3.5,
  } = props;

  if (cx == null || cy == null) {
    return null;
  }

  const size = r + 1;

  return (
    <polygon
      points={`
        ${cx},${cy - size}
        ${cx + size},${cy}
        ${cx},${cy + size}
        ${cx - size},${cy}
      `}
      fill={fill}
      stroke={stroke}
      strokeWidth={1.5}
    />
  );
}

export default function IngestionChartCard({
  emailSeries,
  taskSeries,
  loading,
  days = 7,
}) {
  const [selectedSeries, setSelectedSeries] = useState(null);
  const cardRef = useRef(null);

  const data = useMemo(
    () =>
      emailSeries.map((email, index) => ({
        day: email.label,
        "Emails Received": email.count,
        "Unique Tasks": taskSeries[index]?.count ?? 0,
      })),
    [emailSeries, taskSeries]
  );

  /*
   * Reset the chart to its default state whenever the user
   * clicks anywhere outside this card.
   */
  useEffect(() => {
    const handleOutsideClick = (event) => {
      if (
        cardRef.current &&
        !cardRef.current.contains(event.target)
      ) {
        setSelectedSeries(null);
      }
    };

    document.addEventListener("mousedown", handleOutsideClick);

    return () => {
      document.removeEventListener(
        "mousedown",
        handleOutsideClick
      );
    };
  }, []);

  const handleSeriesClick = (series) => {
    setSelectedSeries((current) =>
      current === series ? null : series
    );
  };

  const chartHasData = data.length > 0;

  const showEmails =
    selectedSeries === null || selectedSeries === "emails";

  const showTasks =
    selectedSeries === null || selectedSeries === "tasks";

  return (
    <div
      ref={cardRef}
      className="flex flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card"
    >
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h3 className="text-[15px] font-semibold text-ink-900">
            Email Processing vs. Unique Tasks
          </h3>

          <p className="mt-1 text-[11px] text-ink-300">
            Last {days} days
          </p>
        </div>

        {!loading && chartHasData && (
          <span className="text-[11px] text-ink-300">
            Daily volume
          </span>
        )}
      </div>

      {/* Chart */}
      {loading ? (
        <div className="mt-5 h-64 animate-pulse rounded-xl bg-ink-900/5" />
      ) : !chartHasData ? (
        <div className="flex h-64 items-center justify-center">
          <div className="text-center">
            <p className="text-sm font-medium text-ink-700">
              No trend data available
            </p>

            <p className="mt-1 text-xs text-ink-300">
              Data will appear as email activity is recorded.
            </p>
          </div>
        </div>
      ) : (
        <div className="mt-3 h-64">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart
              data={data}
              margin={{
                top: 12,
                right: 12,
                left: -14,
                bottom: 4,
              }}
            >
              <CartesianGrid
                strokeDasharray="2 4"
                vertical={false}
                stroke="#eef0f5"
              />

              <XAxis
                dataKey="day"
                tick={{
                  fontSize: 11,
                  fill: "#a4a8ba",
                }}
                axisLine={false}
                tickLine={false}
                dy={6}
              />

              <YAxis
                tick={{
                  fontSize: 11,
                  fill: "#a4a8ba",
                }}
                axisLine={false}
                tickLine={false}
                width={38}
                tickFormatter={(value) =>
                  Number(value).toLocaleString()
                }
              />

              <Tooltip
                content={<EnterpriseTooltip />}
                cursor={{
                  stroke: "#dfe2ea",
                  strokeWidth: 1,
                  strokeDasharray: "4 4",
                }}
                offset={12}
              />

              {/* Unique Tasks */}
              {showTasks && (
                <Line
                  type="monotone"
                  dataKey={SERIES.tasks.key}
                  name={SERIES.tasks.label}
                  stroke={SERIES.tasks.color}
                  strokeWidth={2}
                  strokeOpacity={
                    selectedSeries === "tasks" ? 1 : 0.65
                  }
                  strokeLinecap="round"
                  activeDot={{
                    r: 5,
                    strokeWidth: 2,
                    stroke: "#ffffff",
                  }}
                  dot={
                    <TaskDot
                      stroke={SERIES.tasks.color}
                      fill="#ffffff"
                      r={3.5}
                    />
                  }
                  connectNulls
                />
              )}

              {/* Emails Received */}
              {showEmails && (
                <Line
                  type="monotone"
                  dataKey={SERIES.emails.key}
                  name={SERIES.emails.label}
                  stroke={SERIES.emails.color}
                  strokeWidth={2.5}
                  strokeOpacity={
                    selectedSeries === "emails" ? 1 : 0.95
                  }
                  strokeLinecap="round"
                  activeDot={{
                    r: 5,
                    strokeWidth: 2,
                    stroke: "#ffffff",
                  }}
                  dot={{
                    r: 3,
                    strokeWidth: 1.5,
                    stroke: SERIES.emails.color,
                    fill: "#ffffff",
                  }}
                  connectNulls
                />
              )}
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* Series selector */}
      <div className="mt-2 flex items-center gap-5">
        {/* Emails Received */}
        <button
          type="button"
          onClick={() => handleSeriesClick("emails")}
          className={`group flex items-center gap-2 rounded-md px-1.5 py-1 text-xs transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-200 ${
            selectedSeries === "emails"
              ? "text-ink-900"
              : "text-ink-500"
          }`}
          aria-pressed={selectedSeries === "emails"}
        >
          <span
            className="h-2.5 w-2.5 rounded-full border-2 bg-white transition-opacity"
            style={{
              borderColor: SERIES.emails.color,
              opacity:
                selectedSeries === null ||
                selectedSeries === "emails"
                  ? 1
                  : 0.35,
            }}
          />

          <span
            className={`transition-colors ${
              selectedSeries === "emails"
                ? "font-medium"
                : "group-hover:text-ink-900"
            }`}
          >
            Emails Received
          </span>
        </button>

        {/* Unique Tasks */}
        <button
          type="button"
          onClick={() => handleSeriesClick("tasks")}
          className={`group flex items-center gap-2 rounded-md px-1.5 py-1 text-xs transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-200 ${
            selectedSeries === "tasks"
              ? "text-ink-900"
              : "text-ink-500"
          }`}
          aria-pressed={selectedSeries === "tasks"}
        >
          <span
            className="relative flex h-3 w-3 items-center justify-center transition-opacity"
            style={{
              opacity:
                selectedSeries === null ||
                selectedSeries === "tasks"
                  ? 1
                  : 0.35,
            }}
          >
            <span
              className="h-2 w-2 rotate-45 border-[1.5px] bg-white"
              style={{
                borderColor: SERIES.tasks.color,
              }}
            />
          </span>

          <span
            className={`transition-colors ${
              selectedSeries === "tasks"
                ? "font-medium"
                : "group-hover:text-ink-900"
            }`}
          >
            Unique Tasks
          </span>
        </button>
      </div>

      <p className="mt-2 text-[10px] text-ink-300">
        Click a series to view it separately.
      </p>
    </div>
  );
}