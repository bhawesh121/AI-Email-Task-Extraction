CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_task_search_text_trgm
    ON tasks
    USING GIN (
        (
            lower(
                regexp_replace(
                    (
                        COALESCE(title, '')
                        || ' ' || COALESCE(assignee, '')
                        || ' ' || COALESCE(description, '')
                        || ' ' || COALESCE(source_subject, '')
                    ),
                    '[^[:alnum:]]+',
                    ' ',
                    'g'
                )
            )
        ) gin_trgm_ops
    );
