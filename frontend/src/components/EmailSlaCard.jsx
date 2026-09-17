import React, { useCallback, useEffect, useState } from "react";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { ClockIcon, FlagIcon } from "./Icons";
import { api } from "../lib/api";

// Replaces the former "Deduplication Summary" placeholder.
//
// Backend remains the source of truth for SLA state and all dashboard
// numbers. This component only renders the API response.
//
// The breached-email section is intentionally kept inline because this
// codebase does not have a dedicated Emails page.
//
// Important UI behavior:
// - The breached list is fetched fresh whenever it is opened.
// - Only records whose backend status is BREACHED are rendered.
// - If the latest summary says there are no breached emails, any stale
//   breached list is cleared and the section is closed.
// - Breached-email loading errors are kept separate from the main SLA error.
export default function EmailSlaCard() {
  const [summary, setSummary] = useState(null);
  const [trend, setTrend] = useState([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [showBreached, setShowBreached] = useState(false);
  const [breached, setBreached] = useState([]);
  const [breachedLoading, setBreachedLoading] = useState(false);
  const [breachedError, setBreachedError] = useState(null);

  const loadSlaData = useCallback(async (showMainLoader = false) => {
    if (showMainLoader) {
      setLoading(true);
    }

    try {
      const [summaryRes, trendRes] = await Promise.all([
        api.getSlaSummary(),
        api.getSlaTrend(7),
      ]);

      setSummary(summaryRes);

      setTrend(
        trendRes.map((point) => ({
          day: point.date?.slice(5) ?? "",
          total: point.total,
          completed: point.completed,
          waiting: point.waiting,
          breached: point.breached,
        }))
      );

      setError(null);

      // If the backend now says there are no breached emails,
      // invalidate any previously loaded breached records.
      if (summaryRes.breached === 0) {
        setBreached([]);
        setShowBreached(false);
        setBreachedError(null);
      }
    } catch (err) {
      setError("Unable to load SLA data");
    } finally {
      if (showMainLoader) {
        setLoading(false);
      }
    }
  }, []);

  const loadBreachedEmails = useCallback(async () => {
    setBreachedLoading(true);
    setBreachedError(null);

    try {
      const [summaryRes, rows] = await Promise.all([
        api.getSlaSummary(),
        api.getBreachedEmails(),
      ]);

      // Refresh the summary at the same time so the UI is consistent
      // with the breached list being displayed.
      setSummary(summaryRes);

      // Defense in depth:
      // the backend endpoint should already return BREACHED rows only,
      // but the frontend must never render another status in this section.
      const breachedRows = (rows ?? []).filter(
        (row) => row?.status === "BREACHED"
      );

      if (summaryRes.breached === 0 || breachedRows.length === 0) {
        setBreached([]);
      } else {
        setBreached(breachedRows);
      }

      setShowBreached(true);
    } catch (err) {
      setBreached([]);
      setBreachedError("Unable to load breached emails");
    } finally {
      setBreachedLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function loadInitialData() {
      try {
        const [summaryRes, trendRes] = await Promise.all([
          api.getSlaSummary(),
          api.getSlaTrend(7),
        ]);

        if (cancelled) {
          return;
        }

        setSummary(summaryRes);

        setTrend(
          trendRes.map((point) => ({
            day: point.date?.slice(5) ?? "",
            total: point.total,
            completed: point.completed,
            waiting: point.waiting,
            breached: point.breached,
          }))
        );

        setError(null);

        if (summaryRes.breached === 0) {
          setBreached([]);
          setShowBreached(false);
        }
      } catch (err) {
        if (!cancelled) {
          setError("Unable to load SLA data");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    loadInitialData();

    return () => {
      cancelled = true;
    };
  }, []);

  // Keep the local breached list synchronized with the backend summary.
  //
  // Example:
  // BREACHED → COMPLETED
  // summary.breached becomes 0
  // stale breached records must disappear from the UI immediately.
  useEffect(() => {
    if (summary?.breached === 0) {
      setBreached([]);
      setShowBreached(false);
      setBreachedError(null);
    }
  }, [summary?.breached]);

  async function handleToggleBreached() {
    // Closing the section does not need another API request.
    if (showBreached) {
      setShowBreached(false);
      return;
    }

    // Always fetch fresh data when opening.
    //
    // This is important because an SLA can change between:
    // - the original dashboard load
    // - the user clicking "View breached emails"
    // - the email becoming COMPLETED after a Sent Items reply
    await loadBreachedEmails();
  }

  function formatDeadline(value) {
    if (!value) {
      return "deadline unavailable";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
      return "deadline unavailable";
    }

    return `deadline ${date.toLocaleString()}`;
  }

  function renderDelay(breachedEmail) {
    if (breachedEmail?.delayMinutes == null) {
      return null;
    }

    const delayMinutes = Number(breachedEmail.delayMinutes);

    if (!Number.isFinite(delayMinutes)) {
      return null;
    }

    // Only breached records are allowed into this list.
    // A negative delay therefore should not be displayed as "late".
    if (delayMinutes <= 0) {
      return null;
    }

    return ` · ${delayMinutes}m late`;
  }

  return (
    <div className="flex flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      <h3 className="text-[15px] font-semibold text-ink-900">
        Email SLA &amp; Responsiveness
      </h3>

      {loading ? (
        <div className="mt-4 h-40 animate-pulse rounded-xl bg-ink-900/5" />
      ) : error ? (
        <p className="mt-4 text-sm text-red-500">{error}</p>
      ) : (
        <>
          <div className="mt-4 flex items-center justify-between">
            <div>
              <p className="text-2xl font-semibold tabular-nums text-ink-900">
                {summary.complianceRate == null
                  ? "—"
                  : `${summary.complianceRate.toFixed(1)}%`}
              </p>

              <p className="text-xs text-ink-300">
                SLA compliance today
              </p>
            </div>

            <div className="text-right text-xs text-ink-300">
              <p>
                <span className="font-semibold text-ink-700">
                  {summary.completed}
                </span>{" "}
                / {summary.total} responded in time
              </p>
            </div>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-2">
            <div className="flex items-center gap-2 rounded-xl bg-amber-50 p-2.5">
              <ClockIcon color="#f97316" />

              <div>
                <p className="text-sm font-semibold text-ink-900">
                  {summary.waiting}
                </p>

                <p className="text-[11px] text-ink-300">
                  Waiting
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2 rounded-xl bg-red-50 p-2.5">
              <FlagIcon color="#ef4444" />

              <div>
                <p className="text-sm font-semibold text-ink-900">
                  {summary.breached}
                </p>

                <p className="text-[11px] text-ink-300">
                  Breached
                </p>
              </div>
            </div>
          </div>

          {trend.length > 0 && (
            <div className="mt-4 h-28">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart
                  data={trend}
                  margin={{
                    top: 4,
                    right: 4,
                    left: -28,
                    bottom: 0,
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
                      fontSize: 10,
                      fill: "#a4a8ba",
                    }}
                    axisLine={false}
                    tickLine={false}
                  />

                  <YAxis hide />

                  <Tooltip
                    contentStyle={{
                      fontSize: 12,
                      borderRadius: 8,
                    }}
                    formatter={(value, name) => [value, name]}
                  />

                  <Line
                    type="monotone"
                    dataKey="completed"
                    name="Completed"
                    stroke="#22c55e"
                    strokeWidth={2}
                    dot={false}
                  />

                  <Line
                    type="monotone"
                    dataKey="waiting"
                    name="Waiting"
                    stroke="#f97316"
                    strokeWidth={2}
                    dot={false}
                  />

                  <Line
                    type="monotone"
                    dataKey="breached"
                    name="Breached"
                    stroke="#ef4444"
                    strokeWidth={2}
                    dot={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}

          {/* Only expose the breached action when there are actually
              breached emails according to the latest backend summary. */}
          {summary.breached > 0 && (
            <>
              <button
                type="button"
                onClick={handleToggleBreached}
                disabled={breachedLoading}
                className="mt-3 flex w-fit items-center gap-1 text-xs font-medium text-brand-600 hover:text-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {showBreached ? "Hide" : "View"} breached emails
              </button>

              {showBreached && (
                <div className="mt-2 max-h-48 space-y-1.5 overflow-y-auto rounded-lg bg-ink-900/[0.03] p-2">
                  {breachedLoading ? (
                    <p className="p-2 text-xs text-ink-300">
                      Loading…
                    </p>
                  ) : breachedError ? (
                    <p className="p-2 text-xs text-red-500">
                      {breachedError}
                    </p>
                  ) : breached.length === 0 ? (
                    <p className="p-2 text-xs text-ink-300">
                      No breached emails 🎉
                    </p>
                  ) : (
                    breached.map((b) => (
                      <div
                        key={b.id}
                        className="rounded-md bg-white p-2 text-xs shadow-sm"
                      >
                        <p className="truncate font-medium text-ink-900">
                          {b.subject || "(no subject)"}
                        </p>

                        <p className="truncate text-ink-300">
                          {b.customerEmail || "Unknown customer"} ·{" "}
                          {formatDeadline(b.slaDeadlineAt)}
                          {renderDelay(b)}
                        </p>
                      </div>
                    ))
                  )}
                </div>
              )}
            </>
          )}
        </>
      )}
    </div>
  );
}