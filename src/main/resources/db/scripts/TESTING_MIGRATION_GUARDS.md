# Testing the V15 / V18 migration guards (not just the live constraint)

The script `test_live_active_duplicate_constraint.sql` proves the DEPLOYED constraint blocks
a duplicate insert right now. It does NOT prove that V15/V18 correctly REFUSE TO APPLY when
run against a database that already has dirty data — that guard has never actually fired in
your environment, because your DB was presumably clean when you migrated it. This is the
"safe migration path" claim from the original spec, and it deserves its own test.

This needs a throwaway scratch database, since it means partially migrating, then
deliberately inserting dirty data, then continuing.

## Steps (adjust host/port/user to match your setup — yours is localhost:5433)

```powershell
# 1. Create a throwaway database. Do NOT use your real "aiassistant" db.
createdb -h localhost -p 5433 -U <user> aiassistant_guard_test

# 2. Apply migrations V1 through V14 only (NOT V15 yet), in order.
#    Run each file from src/main/resources/db/migration/ with psql -f,
#    in numeric order: V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11, V12, V13, V14.
psql -h localhost -p 5433 -U <user> -d aiassistant_guard_test -f src/main/resources/db/migration/V1__create_tasks_table.sql
psql -h localhost -p 5433 -U <user> -d aiassistant_guard_test -f src/main/resources/db/migration/V2__add_task_source_metadata.sql
# ... continue through V14__add_normalized_sender.sql ...

# 3. Seed TWO active "duplicate" tasks directly — same
#    (source_mailbox, normalized_sender, task_fingerprint), both ACTIVE.
#    This simulates real historical duplicate data that the old
#    email.id()-in-fingerprint bug would have allowed to exist.
psql -h localhost -p 5433 -U <user> -d aiassistant_guard_test -c "
INSERT INTO tasks (id, title, priority, status, source_mailbox, normalized_sender, task_fingerprint, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'Dirty duplicate 1', 'MEDIUM', 'NEW', 'mbx', 'sender@example.com', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', now(), now()),
  (gen_random_uuid(), 'Dirty duplicate 2', 'MEDIUM', 'NEW', 'mbx', 'sender@example.com', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', now(), now());
"
# Note: this succeeds because V15's constraint doesn't exist in this
# scratch DB yet — that's the point.

# 4. Now try to apply V15. EXPECT THIS TO FAIL with the guard's message.
psql -h localhost -p 5433 -U <user> -d aiassistant_guard_test -f src/main/resources/db/migration/V15__add_active_duplicate_task_index.sql
```

**Expected output at step 4** — the migration should abort with something like:

```
ERROR:  V15 blocked: 1 (source_mailbox, normalized_sender, task_fingerprint) group(s) have
more than one ACTIVE task. Run db/scripts/detect_active_duplicate_tasks.sql to inspect them...
```

If you see that, the guard works as designed. If V15 applies silently instead, that's a real
bug — the guard isn't functioning and should not be trusted on real production data.

## Then test remediation clears the way

```powershell
# 5. Run the detection report — should list the group you seeded.
psql -h localhost -p 5433 -U <user> -d aiassistant_guard_test -f src/main/resources/db/scripts/detect_active_duplicate_tasks.sql

# 6. Run remediation (archives all but the oldest of each group).
#    This script requires an explicit COMMIT; at the end — read the
#    RETURNING output it prints before committing.
psql -h localhost -p 5433 -U <user> -d aiassistant_guard_test -f src/main/resources/db/scripts/archive_duplicate_active_tasks.sql
# then manually run: psql ... -c "COMMIT;"   (or re-run with psql -1 -f for single-transaction mode)

# 7. Re-run V15 — should now succeed.
psql -h localhost -p 5433 -U <user> -d aiassistant_guard_test -f src/main/resources/db/migration/V15__add_active_duplicate_task_index.sql
```

## V18's guard follows the identical pattern

Same idea, but V18 recomputes fingerprints (dropping `email.id()`) and its guard checks for
POST-recompute collisions — so the dirty data needs two ACTIVE tasks that have DIFFERENT
`task_fingerprint` values today (as the old buggy algorithm would produce, since it baked in
different email ids) but would collide once recomputed. Use
`db/scripts/detect_fingerprint_collisions_after_email_id_removal.sql` and
`db/scripts/archive_fingerprint_collisions_after_email_id_removal.sql` instead of the V15
pair — same overall flow, different SQL, since it can't rely on the raw stored column.

## Cleanup

```powershell
dropdb -h localhost -p 5433 -U <user> aiassistant_guard_test
```
