CREATE TABLE tasks (
    id UUID PRIMARY KEY,

    title VARCHAR(500) NOT NULL,

    description TEXT,

    assignee VARCHAR(255),

    assignee_email VARCHAR(320),

    due_date DATE,

    priority VARCHAR(20) NOT NULL,

    status VARCHAR(20) NOT NULL,

    source_email_id VARCHAR(500),

    source_subject VARCHAR(500),

    source_sender VARCHAR(320),

    source_mailbox VARCHAR(320),

    source_thread_id VARCHAR(500),

    ai_reason TEXT,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_task_status
    ON tasks(status);

CREATE INDEX idx_task_assignee
    ON tasks(assignee);

CREATE INDEX idx_task_due_date
    ON tasks(due_date);

CREATE INDEX idx_task_priority
    ON tasks(priority);