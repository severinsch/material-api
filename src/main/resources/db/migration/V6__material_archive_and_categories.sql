-- Archived materials: no longer in use (sold, worn out), but kept with their history, since materials that were lent,
-- bought or have items can't be deleted. NULL for materials in use. Clients hide archived materials where new use
-- starts.
ALTER TABLE materials ADD COLUMN archived_at TIMESTAMPTZ;

-- ========= CATEGORIES =========
-- From now on the API trims categories and stores blank ones as NULL; existing ones are brought into that shape.
-- Other names are left as they are: the API trims them on receive from now on, and there is no real data yet.
UPDATE materials
SET category = NULLIF(btrim(category), '')
WHERE category IS DISTINCT FROM NULLIF(btrim(category), '');
