-- Groups get one or more amounts ("Ausführungen") instead of a single `required_units` and `is_optional`: a Jurte is
-- packed with an Einbein (1 Jurtenstange) or a Dreibein (3), and an optional group is a group with an amount of 0.

-- The amounts a group can be packed with, e.g. Einbein = 1 and Dreibein = 3 Jurtenstangen, or "ohne" = 0 and
-- "mit" = 12 Fensterplanen. The packing list picks one per kit; the first one is the default.
CREATE TABLE kit_group_amounts
(
    group_id       UUID         NOT NULL,
    tenant_id      UUID         NOT NULL DEFAULT current_tenant_id(),
    -- Display order within the group; the first amount is the default.
    position       INTEGER      NOT NULL,
    label          VARCHAR(100), -- e.g. "Einbein"; NULL only for a group's single amount (checked by the API).
    required_units INTEGER      NOT NULL CHECK (required_units >= 0),
    PRIMARY KEY (group_id, position),
    CONSTRAINT kit_group_amounts_group_id_fkey FOREIGN KEY (group_id, tenant_id)
        REFERENCES kit_groups (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT kit_group_amounts_label_key UNIQUE (group_id, label)
);

ALTER TABLE kit_group_amounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kit_group_amounts
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- A group keeps its amount; an optional group becomes "ohne" (the default, so it stays excluded) and "mit".
-- tenant_id is copied, since current_tenant_id() is NULL while migrations run.
INSERT INTO kit_group_amounts (group_id, tenant_id, position, label, required_units)
SELECT id, tenant_id, 0, NULL, required_units FROM kit_groups WHERE NOT is_optional
UNION ALL
SELECT id, tenant_id, 0, 'ohne', 0 FROM kit_groups WHERE is_optional
UNION ALL
SELECT id, tenant_id, 1, 'mit', required_units FROM kit_groups WHERE is_optional;

ALTER TABLE kit_groups
    DROP COLUMN required_units,
    DROP COLUMN is_optional;
