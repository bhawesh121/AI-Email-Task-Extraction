import React, { useEffect, useMemo, useRef, useState } from "react";
import { PieChart, Pie, Cell, ResponsiveContainer } from "recharts";

export default function TaskStatusCard({ breakdown, total, loading }) {
  const [activeKey, setActiveKey] = useState(null);
  const cardRef = useRef(null);

  const activeStatus = useMemo(
    () =>
      breakdown.find(
        (status) => status.key === activeKey
      ) ?? null,
    [activeKey, breakdown]
  );

  const getPercentage = (value) =>
    total ? Math.round((value / total) * 100) : 0;

  /*
   * Reset the selected status whenever the user clicks
   * anywhere outside this card.
   */
  useEffect(() => {
    const handleOutsideClick = (event) => {
      if (
        cardRef.current &&
        !cardRef.current.contains(event.target)
      ) {
        setActiveKey(null);
      }
    };

    document.addEventListener(
      "mousedown",
      handleOutsideClick
    );

    return () => {
      document.removeEventListener(
        "mousedown",
        handleOutsideClick
      );
    };
  }, []);

  return (
    <div
      ref={cardRef}
      className="flex min-h-[366px] flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card"
    >
      <div className="flex items-start justify-between">
        <div>
          <h3 className="text-[15px] font-semibold text-ink-900">
            Task Status
          </h3>

          <p className="mt-1 text-[11px] text-ink-300">
            Distribution by current status
          </p>
        </div>

        {!loading && breakdown.length > 0 && (
          <span className="rounded-full bg-ink-50 px-2 py-1 text-[10px] font-medium text-ink-400">
            {breakdown.length} statuses
          </span>
        )}
      </div>

      {loading ? (
        <div className="mt-6 flex flex-1 items-center justify-center">
          <div className="h-36 w-full animate-pulse rounded-xl bg-ink-900/5" />
        </div>
      ) : breakdown.length === 0 ? (
        <div className="flex flex-1 items-center justify-center text-sm text-ink-300">
          No task status data
        </div>
      ) : (
        <>
          <div className="mt-3 grid grid-cols-[132px_minmax(0,1fr)] items-center gap-3">
            <div
              className="relative h-[132px] w-[132px] shrink-0"
              onMouseLeave={() => setActiveKey(null)}
              aria-label="Task status distribution"
            >
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={breakdown}
                    dataKey="value"
                    nameKey="label"
                    innerRadius={47}
                    outerRadius={63}
                    paddingAngle={2.5}
                    strokeWidth={0}
                    isAnimationActive
                  >
                    {breakdown.map((entry) => (
                      <Cell
                        key={entry.key}
                        fill={entry.color}
                        fillOpacity={
                          activeKey &&
                          activeKey !== entry.key
                            ? 0.3
                            : 1
                        }
                      />
                    ))}
                  </Pie>
                </PieChart>
              </ResponsiveContainer>

              <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                <span className="text-[25px] font-bold leading-none text-ink-900">
                  {total.toLocaleString()}
                </span>

                <span className="mt-1 text-[10px] text-ink-300">
                  Total tasks
                </span>
              </div>
            </div>

            <div className="min-w-0 space-y-1.5">
              {breakdown.map((status) => {
                const percentage = getPercentage(status.value);
                const isActive =
                  activeKey === status.key;

                return (
                  <button
                    key={status.key}
                    type="button"
                    onMouseEnter={() =>
                      setActiveKey(status.key)
                    }
                    onFocus={() =>
                      setActiveKey(status.key)
                    }
                    onClick={() =>
                      setActiveKey((key) =>
                        key === status.key
                          ? null
                          : status.key
                      )
                    }
                    className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-200 ${
                      isActive
                        ? "bg-ink-50"
                        : "hover:bg-ink-50/70"
                    }`}
                    aria-pressed={isActive}
                  >
                    <span
                      className="h-2.5 w-2.5 shrink-0 rounded-full"
                      style={{
                        background: status.color,
                      }}
                    />

                    <span className="min-w-0 flex-1 truncate text-[13px] text-ink-700">
                      {status.label}
                    </span>

                    <span className="shrink-0 text-[13px] font-semibold tabular-nums text-ink-900">
                      {status.value.toLocaleString()}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="mt-5 border-t border-ink-900/5 pt-3">
            <div className="mb-2 flex items-center justify-between text-[10px] text-ink-300">
              <span>
                {activeStatus
                  ? activeStatus.label
                  : "All tasks"}
              </span>

              <span className="font-medium text-ink-500">
                {activeStatus
                  ? `${getPercentage(
                      activeStatus.value
                    )}%`
                  : `${total.toLocaleString()} total`}
              </span>
            </div>

            <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-ink-100">
              {breakdown.map((status) => (
                <div
                  key={status.key}
                  className="h-full transition-all duration-200"
                  style={{
                    width: `${getPercentage(
                      status.value
                    )}%`,
                    background: status.color,
                    opacity:
                      activeKey &&
                      activeKey !== status.key
                        ? 0.3
                        : 1,
                  }}
                  onMouseEnter={() =>
                    setActiveKey(status.key)
                  }
                  aria-hidden="true"
                />
              ))}
            </div>

            <p className="mt-2 text-[10px] text-ink-300">
              Hover or click a status to highlight its share.
            </p>
          </div>
        </>
      )}
    </div>
  );
}