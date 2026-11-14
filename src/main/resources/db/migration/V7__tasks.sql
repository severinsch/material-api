-- Tasks ("Aufgaben") linked to material: one-off and recurring tasks, rules that keep one recurring task per item of a
-- material, and routines that create several tasks around a date (e.g. before a camp). See PLAN.md (T-7).
-- All tables are tenant-owned, following the convention in README.md.

CREATE TYPE task_label AS ENUM ('INSPECTION', 'REPAIR', 'CARE', 'PURCHASE', 'OTHER');

-- Composite foreign keys from tasks need this on the referenced table.
ALTER TABLE damage_reports ADD CONSTRAINT damage_reports_id_tenant_id_key UNIQUE (id, tenant_id);

-- ========= RULES =========
-- One recurring task per item (active or in repair) of an individually tracked material, also for items added later.
CREATE TABLE task_rules
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL DEFAULT current_tenant_id() REFERENCES tenants (id) ON DELETE RESTRICT,
    title         VARCHAR(255) NOT NULL,
    material_id   UUID         NOT NULL,
    repeat_months INTEGER      NOT NULL CHECK (repeat_months > 0),
    -- The first due date of an item counts from its purchase date if known, otherwise from the day the task is made.
    from_purchase BOOLEAN      NOT NULL,
    assignee_id   UUID REFERENCES users ON DELETE SET NULL,
    label         task_label   NOT NULL,
    note          TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT task_rules_id_tenant_id_key UNIQUE (id, tenant_id),
    CONSTRAINT task_rules_material_id_fkey FOREIGN KEY (material_id, tenant_id)
        REFERENCES materials (id, tenant_id) ON DELETE CASCADE
);

-- ========= ROUTINES =========
-- Started by hand for an occasion and a date ("day 0", e.g. the start of a camp); each step becomes a task due at
-- that date plus its offset.
CREATE TABLE task_routines
(
    id         UUID PRIMARY KEY,
    tenant_id  UUID         NOT NULL DEFAULT current_tenant_id() REFERENCES tenants (id) ON DELETE RESTRICT,
    name       VARCHAR(100) NOT NULL,
    -- What day 0 is, e.g. "Lagerbeginn".
    anchor     VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT task_routines_id_tenant_id_key UNIQUE (id, tenant_id),
    CONSTRAINT task_routines_name_key UNIQUE (tenant_id, name)
);

CREATE TABLE task_routine_steps
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL DEFAULT current_tenant_id(),
    routine_id  UUID         NOT NULL,
    position    INTEGER      NOT NULL,
    -- Days relative to day 0; negative is before.
    offset_days INTEGER      NOT NULL CHECK (offset_days BETWEEN -365 AND 365),
    title       VARCHAR(255) NOT NULL,
    note        TEXT,
    assignee_id UUID REFERENCES users ON DELETE SET NULL,
    label       task_label   NOT NULL,
    urgent      BOOLEAN      NOT NULL,
    CONSTRAINT task_routine_steps_id_tenant_id_key UNIQUE (id, tenant_id),
    CONSTRAINT task_routine_steps_routine_id_fkey FOREIGN KEY (routine_id, tenant_id)
        REFERENCES task_routines (id, tenant_id) ON DELETE CASCADE
);

-- What a step's task will be about: a material (any tracking type) or a specific item.
CREATE TABLE task_routine_step_links
(
    tenant_id   UUID NOT NULL DEFAULT current_tenant_id(),
    step_id     UUID NOT NULL,
    material_id UUID,
    item_id     UUID,
    CHECK (num_nonnulls(material_id, item_id) = 1),
    UNIQUE (step_id, material_id),
    UNIQUE (step_id, item_id),
    FOREIGN KEY (step_id, tenant_id) REFERENCES task_routine_steps (id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (material_id, tenant_id) REFERENCES materials (id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (item_id, tenant_id) REFERENCES individual_items (id, tenant_id) ON DELETE CASCADE
);

-- ========= TASKS =========
-- A task is open until completed_at is set. The note is plain text; lines starting with "[ ] " or "[x] " are a
-- checklist, which only clients interpret.
CREATE TABLE tasks
(
    id               UUID PRIMARY KEY,
    tenant_id        UUID         NOT NULL DEFAULT current_tenant_id() REFERENCES tenants (id) ON DELETE RESTRICT,
    title            VARCHAR(255) NOT NULL,
    note             TEXT,
    due_date         DATE,
    urgent           BOOLEAN      NOT NULL DEFAULT false,
    label            task_label   NOT NULL,
    assignee_id      UUID REFERENCES users ON DELETE SET NULL,
    -- Completing a recurring task creates the next one, by default repeat_months after completion.
    repeat_months    INTEGER CHECK (repeat_months > 0),
    completed_at     TIMESTAMPTZ,
    -- The completing member's name as of then; kept after their account is gone.
    completed_by     VARCHAR(255),
    completion_note  TEXT,
    -- The task whose completion created this one: the history of a recurring task.
    previous_id      UUID,
    rule_id          UUID,
    routine_id       UUID,
    -- The routine run this task belongs to, e.g. "Pfingstlager 2027"; kept after the routine is deleted.
    occasion         VARCHAR(100),
    damage_report_id UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT tasks_id_tenant_id_key UNIQUE (id, tenant_id),
    CONSTRAINT tasks_completed_check CHECK ((completed_at IS NULL) = (completed_by IS NULL)),
    -- SET NULL (column) keeps tenant_id, which is part of the keys (Postgres 15+).
    CONSTRAINT tasks_previous_id_fkey FOREIGN KEY (previous_id, tenant_id)
        REFERENCES tasks (id, tenant_id) ON DELETE SET NULL (previous_id),
    CONSTRAINT tasks_rule_id_fkey FOREIGN KEY (rule_id, tenant_id)
        REFERENCES task_rules (id, tenant_id) ON DELETE SET NULL (rule_id),
    CONSTRAINT tasks_routine_id_fkey FOREIGN KEY (routine_id, tenant_id)
        REFERENCES task_routines (id, tenant_id) ON DELETE SET NULL (routine_id),
    CONSTRAINT tasks_damage_report_id_fkey FOREIGN KEY (damage_report_id, tenant_id)
        REFERENCES damage_reports (id, tenant_id) ON DELETE SET NULL (damage_report_id)
);

-- What a task is about: materials (any tracking type) and specific items, several per task.
CREATE TABLE task_links
(
    tenant_id   UUID NOT NULL DEFAULT current_tenant_id(),
    task_id     UUID NOT NULL,
    material_id UUID,
    item_id     UUID,
    CHECK (num_nonnulls(material_id, item_id) = 1),
    UNIQUE (task_id, material_id),
    UNIQUE (task_id, item_id),
    FOREIGN KEY (task_id, tenant_id) REFERENCES tasks (id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (material_id, tenant_id) REFERENCES materials (id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (item_id, tenant_id) REFERENCES individual_items (id, tenant_id) ON DELETE CASCADE
);

-- Lookups by task_id and step_id use the unique constraints of the link tables.
CREATE INDEX idx_task_rules_material_id ON task_rules (material_id);
CREATE INDEX idx_task_routine_steps_routine_id ON task_routine_steps (routine_id);
CREATE INDEX idx_tasks_tenant_id ON tasks (tenant_id);
CREATE INDEX idx_tasks_previous_id ON tasks (previous_id);
CREATE INDEX idx_tasks_rule_id ON tasks (rule_id);
CREATE INDEX idx_task_links_material_id ON task_links (material_id);
CREATE INDEX idx_task_links_item_id ON task_links (item_id);

CREATE TRIGGER task_rules_updated_at BEFORE UPDATE ON task_rules FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER task_routines_updated_at BEFORE UPDATE ON task_routines FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tasks_updated_at BEFORE UPDATE ON tasks FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE task_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_routines ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_routine_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_routine_step_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_links ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON task_rules
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON task_routines
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON task_routine_steps
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON task_routine_step_links
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON tasks
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON task_links
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
