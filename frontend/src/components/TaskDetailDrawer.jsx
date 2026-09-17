import React, { useEffect, useMemo } from "react";
import { createPortal } from "react-dom";
import DOMPurify from "dompurify";
import {
  XIcon,
  SparkleIcon,
  EnvelopeOpenIcon,
  UserIcon,
} from "./Icons";
import { formatDate, priorityDot } from "../lib/derive";

const STATUS_OPTIONS = [
  "NEW",
  "IN_PROGRESS",
  "COMPLETED",
  "BLOCKED",
  "ARCHIVED",
];

const PRIORITY_OPTIONS = [
  "LOW",
  "MEDIUM",
  "HIGH",
  "CRITICAL",
];

// Two different backend pipelines populate sourceEmailBody with different
// shapes: GraphEmailService (interactive login) strips to plain text via
// Jsoup; TenantGraphEmailService (automated tenant sync) stores the raw
// Microsoft Graph HTML completely unprocessed, including Word/Outlook
// junk markup (<style>@font-face...>, MsoNormal classes, conditional
// comments). Rather than special-case which pipeline produced a given
// task, every value is sanitized the same way before rendering:
// - Plain text has no tags to strip, so it passes through unchanged.
// - Raw HTML gets reduced to a small, safe set of structural/text tags.
// This runs on every render of an open task, not once at fetch time —
// sanitize-at-render is what actually protects against XSS, since it
// can't be bypassed by a future code path that skips a one-time cleanup.
const EMAIL_BODY_SANITIZE_CONFIG = {
  ALLOWED_TAGS: [
    "p", "br", "div", "span",
    "b", "strong", "i", "em", "u",
    "ul", "ol", "li",
    "a", "blockquote",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "table", "thead", "tbody", "tr", "td", "th",
  ],
  ALLOWED_ATTR: ["href"],  // Images are stripped rather than sanitized-and-kept: a remote <img>
  // in a business email is a common tracking-pixel vector (read
  // receipts), and this drops that risk entirely rather than trying to
  // decide per-image whether it's safe.
  FORBID_TAGS: ["img", "style", "script", "iframe", "object", "embed", "form", "link", "meta"],
  FORBID_ATTR: ["style", "class", "id", "onerror", "onload"],
};

// Any link inside an email body opens in a new tab rather than
// navigating the dashboard itself away, and never grants the opened
// page a handle back to this window (the classic "tabnabbing" gap left
// by target="_blank" without rel="noopener noreferrer").
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A" && node.hasAttribute("href")) {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
  }
});

function initials(name) {
  if (!name) return "?";

  return name
    .split(" ")
    .map((p) => p[0])
    .join("")
    .slice(0, 2)
    .toUpperCase();
}

export default function TaskDetailDrawer({
  task,
  onClose,
  onStatusChange,
  onPriorityChange,
  saving,
}) {
  useEffect(() => {
    function onKey(e) {
      if (e.key === "Escape") {
        onClose();
      }
    }

    document.addEventListener("keydown", onKey);

    return () => {
      document.removeEventListener("keydown", onKey);
    };
  }, [onClose]);

  // Sanitized on every render of the currently-open task, not cached
  // from a prior step in the pipeline — see the config comment above
  // for why this can't be a one-time server-side cleanup instead.
  const sanitizedEmailBody = useMemo(() => {
    if (!task?.sourceEmailBody) return "";
    return DOMPurify.sanitize(task.sourceEmailBody, EMAIL_BODY_SANITIZE_CONFIG);
  }, [task?.sourceEmailBody]);

  if (!task) return null;

  return createPortal(
    <div className="fixed inset-0 z-[9999] flex justify-end">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-ink-900/30 backdrop-blur-[1px]"
        onClick={onClose}
      />

      {/* Drawer */}
      <div className="relative flex h-full w-full max-w-lg flex-col overflow-y-auto bg-white shadow-2xl animate-[slideIn_0.2s_ease-out]">
        <style>{`
          @keyframes slideIn {
            from {
              transform: translateX(24px);
              opacity: 0;
            }
            to {
              transform: translateX(0);
              opacity: 1;
            }
          }

          /*
            The sanitized email body can now contain real structural tags
            (see EMAIL_BODY_SANITIZE_CONFIG's ALLOWED_TAGS) — plain-text
            bodies just have none of these elements, so these rules are
            inert for them. All inline style/class/id attributes are
            stripped during sanitization, so styling has to happen here,
            not carried over from the original email's markup.
          */
          .email-body-content {
            overflow-wrap: anywhere;
            word-break: break-word;
          }

          .email-body-content p,
          .email-body-content div {
            margin: 0 0 0.75rem 0;
          }

          .email-body-content p:last-child,
          .email-body-content div:last-child {
            margin-bottom: 0;
          }

          .email-body-content h1,
          .email-body-content h2,
          .email-body-content h3,
          .email-body-content h4,
          .email-body-content h5,
          .email-body-content h6 {
            margin: 0.75rem 0 0.5rem;
            font-size: 14px;
            font-weight: 600;
          }

          .email-body-content ul,
          .email-body-content ol {
            margin: 0.5rem 0 0.75rem 1.25rem;
          }

          .email-body-content li {
            margin-bottom: 0.25rem;
          }

          .email-body-content a {
            color: inherit;
            text-decoration: underline;
          }

          .email-body-content blockquote {
            margin: 0.5rem 0;
            padding-left: 0.75rem;
            border-left: 2px solid rgb(0 0 0 / 0.1);
            color: rgb(0 0 0 / 0.6);
          }

          .email-body-content table {
            max-width: 100%;
            border-collapse: collapse;
            margin: 0.5rem 0;
          }

          .email-body-content td,
          .email-body-content th {
            padding: 0.25rem 0.5rem;
            border: 1px solid rgb(0 0 0 / 0.1);
          }
        `}</style>

        {/* Header */}
        <div className="flex items-start justify-between gap-4 border-b border-ink-900/5 px-6 py-5">
          <div className="flex items-center gap-2 text-xs font-medium text-brand-600">
            <SparkleIcon />
            Extracted from email
          </div>

          <button
            type="button"
            onClick={onClose}
            aria-label="Close task details"
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-500 hover:bg-ink-900/5"
          >
            <XIcon width={18} height={18} />
          </button>
        </div>

        <div className="flex-1 px-6 py-5">
          {/* Task title */}
          <h2 className="text-xl font-bold leading-snug text-ink-900">
            {task.title}
          </h2>

          {/* Status / Priority */}
          <div className="mt-4 flex flex-wrap items-center gap-2">
            <select
              value={task.status}
              disabled={saving}
              onChange={(e) =>
                onStatusChange(task, e.target.value)
              }
              aria-label="Task status"
              className="rounded-full border border-ink-900/10 bg-ink-900/[0.03] px-3 py-1.5 text-xs font-semibold text-ink-700 disabled:opacity-50"
            >
              {STATUS_OPTIONS.map((status) => (
                <option key={status} value={status}>
                  {status.replace("_", " ")}
                </option>
              ))}
            </select>

            <select
              value={task.priority}
              disabled={saving}
              onChange={(e) =>
                onPriorityChange(task, e.target.value)
              }
              aria-label="Task priority"
              className="rounded-full border border-ink-900/10 bg-ink-900/[0.03] px-3 py-1.5 text-xs font-semibold text-ink-700 disabled:opacity-50"
            >
              {PRIORITY_OPTIONS.map((priority) => (
                <option key={priority} value={priority}>
                  {priority.charAt(0) +
                    priority.slice(1).toLowerCase()}{" "}
                  priority
                </option>
              ))}
            </select>
          </div>

          {/* Description */}
          <div className="mt-6">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-300">
              What needs to happen
            </h3>

            <p className="mt-2 whitespace-pre-wrap text-sm font-normal leading-relaxed text-ink-700">
              {task.description ||
                "No additional description was extracted for this task."}
            </p>
          </div>

          {/* AI reasoning */}
          {task.aiReason && (
            <div className="mt-6 rounded-xl bg-violet-50/60 p-4">
              <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-violet-700">
                <SparkleIcon />
                Why this was flagged
              </h3>

              <p className="mt-2 text-sm font-normal leading-relaxed text-violet-900/80">
                {task.aiReason}
              </p>
            </div>
          )}

          {/* Assignee & due date */}
          <div className="mt-6 grid grid-cols-2 gap-4">
            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-300">
                Assignee
              </h3>

              <div className="mt-2 flex items-center gap-2">
                <div className="flex h-7 w-7 items-center justify-center rounded-full bg-brand-100 text-[11px] font-semibold text-brand-700">
                  {initials(task.assignee)}
                </div>

                <span className="text-sm text-ink-900">
                  {task.assignee || "Unassigned"}
                </span>
              </div>
            </div>

            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-300">
                Due date
              </h3>

              <p className="mt-2 flex items-center gap-1.5 text-sm text-ink-900">
                <span
                  className={`h-1.5 w-1.5 rounded-full ${
                    priorityDot[task.priority] || "bg-ink-300"
                  }`}
                />

                {formatDate(task.dueDate)}
              </p>
            </div>
          </div>

          {/* Source email */}
          <div className="mt-8 rounded-xl border border-ink-900/5 bg-ink-900/[0.015] p-4">
            <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-ink-300">
              <EnvelopeOpenIcon width={14} height={14} />
              Source email
            </h3>

            <div className="mt-3 space-y-2 text-sm">
              {task.sourceSubject && (
                <p className="font-medium text-ink-900">
                  {task.sourceSubject}
                </p>
              )}

              <p className="flex items-center gap-1.5 text-ink-500">
                <UserIcon width={14} height={14} />
                {task.sourceSender || "Unknown sender"}
              </p>

              {task.sourceMailbox && (
                <p className="text-xs text-ink-300">
                  Received in {task.sourceMailbox}
                </p>
              )}

              {task.emailReceivedAt && (
                <p className="text-xs text-ink-300">
                  {formatDate(task.emailReceivedAt)}
                </p>
              )}
            </div>
          </div>

          {/* Full original email body */}
          <div className="mt-6 rounded-xl border border-ink-900/5 bg-white">
            <div className="flex items-center gap-1.5 border-b border-ink-900/5 px-4 py-3">
              <EnvelopeOpenIcon
                width={14}
                height={14}
                className="text-ink-300"
              />

              <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-300">
                Email Body
              </h3>
            </div>

            {sanitizedEmailBody ? (
              <div className="max-h-80 overflow-y-auto px-4 py-4">
                {/*
                  sanitizedEmailBody is DOMPurify output, computed above
                  on every render from the raw field — see
                  EMAIL_BODY_SANITIZE_CONFIG for exactly what's allowed
                  through. This is the one place in the app that uses
                  dangerouslySetInnerHTML, and it's safe specifically
                  because the content passed to it is always the
                  sanitized value, never task.sourceEmailBody directly.
                */}
                <div
                  className="email-body-content whitespace-pre-wrap text-sm leading-relaxed text-ink-700"
                  dangerouslySetInnerHTML={{ __html: sanitizedEmailBody }}
                />
              </div>
            ) : (
              <p className="px-4 py-5 text-sm text-ink-300">
                {task.sourceEmailId
                  ? "The original email content isn't available for this task."
                  : "This task wasn't created from an email."}
              </p>
            )}
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
}