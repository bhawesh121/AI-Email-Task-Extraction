-- V16
-- Add the original Microsoft Graph receivedDateTime for a task's
-- source email, distinct from created_at (row creation time) and
-- excel_last_attempt_at (Excel write time).
--
-- Purely additive: nullable, no constraint, cannot fail on existing
-- data.
--
-- No backfill is possible: EmailProcessing (the queue table) never
-- stored the Graph receivedDateTime either, only its own processing
-- timestamps (created_at, processed_at, claimed_at), none of which
-- represent when the email actually arrived in the mailbox. Tasks
-- created before this migration will have email_received_at = NULL
-- indefinitely; this is expected and should be surfaced as such
-- (e.g. blank cell) rather than approximated from another timestamp.

ALTER TABLE tasks
ADD COLUMN email_received_at TIMESTAMPTZ;
