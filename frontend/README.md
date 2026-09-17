# AI Email Assistant — Dashboard

A Vite + React dashboard that matches the mockup, wired directly to the real
`aiassistant` Spring Boot API — not mock data.

## Where this goes

Drop this `frontend/` folder next to your existing `aiassistant/` backend folder:

```
your-project/
├── aiassistant/     ← existing Spring Boot backend
└── frontend/        ← this folder
```

## Run it

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. It must run on this exact port — the backend's
`WebConfig` only allows CORS from `http://localhost:5173`.

Vite proxies `/api`, `/oauth2`, and `/login` to `http://localhost:8080` (see
`vite.config.js`), so the browser only ever talks to `:5173` and its session
cookie is forwarded straight through. The backend requires you to be signed in
(Spring Security OAuth2 login) for every `/api/**` route, so on first load
you'll be prompted to sign in with Microsoft.

## What's real vs. what's a placeholder

Everything is wired to an existing endpoint in your backend — including writes:

| Feature | Backend endpoint |
|---|---|
| Emails Processed, mailboxes | `GET /api/dashboard/summary`, `GET /api/emails` |
| Tasks Generated, Open/Overdue/High Priority | `GET /api/tasks/summary` (re-fetched live as you change the Filters popover) |
| Task Status donut | `GET /api/tasks/summary` |
| Tasks by Assignee, Upcoming Deadlines, Recent Activity | `GET /api/tasks` (aggregated client-side) |
| **Needs Attention** — combined overdue + high-priority queue, click-through to full task detail | `GET /api/tasks` (aggregated client-side) |
| **Top External Contacts** — email volume by sender | `GET /api/emails`, filtered to `sourceType === "EXTERNAL"` |
| **Employee Workload** — open/overdue/high-priority per assignee | `GET /api/tasks` (aggregated client-side) |
| Email Ingestion vs. Unique Tasks trend (7/14/30-day toggle) | `GET /api/emails` + `GET /api/tasks`, bucketed by day |
| **Tasks & Workload page** — search, filter, sort, paginate | `GET /api/tasks/filter`, `GET /api/tasks/filter-options` |
| **Inline status/priority editing** on the Tasks page | `PATCH /api/tasks/{id}/status`, `PATCH /api/tasks/{id}/priority` |
| **Settings page** — connected mailboxes, email sources | `GET /api/mailboxes`, `GET /api/email-sources` |
| API Connectivity | live `fetch` checks against the endpoints above |

Navigation is real routing (`react-router-dom`) — Dashboard, Tasks & Workload, and
Settings are separate pages sharing one data layer (`src/lib/AppDataContext.jsx`),
so switching pages doesn't re-trigger the initial load. Clicking an item in
"Needs Attention" deep-links to `/tasks?taskId=...`, which opens that task's
detail panel automatically; the "N overdue" / "N high priority" pills link to
`/tasks?attention=overdue` / `?attention=priority`, a quick pre-filter on the
Tasks page.

One section has **no backing endpoint yet**, so rather than fabricate numbers it
says so explicitly in the UI:

- **Deduplication Summary** — the backend already tracks duplicate matches in
  `TaskDuplicateMatchRepository`, it just isn't exposed over REST. Add a
  `GET /api/tasks/duplicates/summary` endpoint and swap the placeholder in
  `src/components/DedupSummaryCard.jsx` for a real fetch.
- Week-over-week **% change** on the stat cards is omitted for the same
  reason — the API has no historical snapshot to diff against yet.

## Project structure

```
src/
├── App.jsx                  — routing shell + sign-in gate
├── pages/
│   ├── DashboardPage.jsx     — the overview screen
│   ├── TasksPage.jsx         — full task table: search/filter/sort/paginate + inline edits
│   └── SettingsPage.jsx      — connected mailboxes & email sources
├── lib/
│   ├── api.js                 — thin fetch client over the real API (GET + PATCH)
│   ├── AppDataContext.jsx      — single shared fetch of all dashboard data, used by every page
│   └── derive.js                — client-side aggregation (assignee counts, trends, etc.)
└── components/                   — one file per dashboard card
```
