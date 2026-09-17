ALTER TABLE employees
    ADD COLUMN microsoft_user_id VARCHAR(100);

ALTER TABLE employees
    ADD CONSTRAINT uk_employee_microsoft_user_id
    UNIQUE (microsoft_user_id);