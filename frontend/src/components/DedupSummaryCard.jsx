import React, { useState } from "react";
import { DedupeIcon } from "./Icons";

// The backend already tracks duplicates (entity.TaskDuplicateMatch /
// repository.TaskDuplicateMatchRepository) but doesn't expose them over
// REST yet. The card is honest that it's not live rather than inventing
// numbers, but keeps the implementation detail (repo/endpoint names) tucked
// behind an optional disclosure instead of putting it in front of everyone
// viewing the dashboard.
export default function DedupSummaryCard() {
  const [showDetails, setShowDetails] = useState(false);

  return (
    <div className="flex flex-col rounded-2xl border border-ink-900/5 bg-white p-5 shadow-card">
      <h3 className="text-[15px] font-semibold text-ink-900">Deduplication Summary</h3>

      <div className="mt-4 flex items-center gap-3">
        <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-violet-50">
          <DedupeIcon />
        </div>
        <div>
          <p className="text-sm font-medium text-ink-900">Coming soon</p>
          <p className="text-xs text-ink-300">This metric isn't available yet</p>
        </div>
      </div>

      <p className="mt-4 text-sm leading-relaxed text-ink-500">
        Duplicate emails are already detected behind the scenes, but this
        summary isn't wired up to show it yet.
      </p>

      <button
        onClick={() => setShowDetails((s) => !s)}
        className="mt-3 flex w-fit items-center gap-1 text-xs font-medium text-ink-300 hover:text-ink-500"
      >
        {showDetails ? "Hide" : "Show"} technical details
      </button>

      {showDetails && (
        <p className="mt-2 rounded-lg bg-ink-900/[0.03] p-3 text-xs leading-relaxed text-ink-500">
          <span className="font-medium text-ink-700">TaskDuplicateMatchRepository</span> already
          records exact and semantic duplicate matches during task extraction. Adding a{" "}
          <code className="rounded bg-white px-1 py-0.5">GET /api/tasks/duplicates/summary</code>{" "}
          endpoint that returns total processed, unique tasks created, and a count broken out by
          match type will let this card render it.
        </p>
      )}
    </div>
  );
}
