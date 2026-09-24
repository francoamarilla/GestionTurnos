-- Horario partido (ENDPOINTS-PENDIENTES.md #7): a day can now have more
-- than one range (9-13 and 16-20), so the (owner, day) primary key goes.
-- is_closed disappears with it: a closed day is simply a day with no
-- ranges, which is how every reader already treats a missing row.
DELETE FROM branch_schedule WHERE is_closed OR open_time IS NULL OR close_time IS NULL;
DELETE FROM professional_schedule WHERE is_closed OR open_time IS NULL OR close_time IS NULL;

ALTER TABLE branch_schedule DROP CONSTRAINT branch_schedule_pkey;
ALTER TABLE branch_schedule DROP COLUMN is_closed;
ALTER TABLE branch_schedule ADD COLUMN id UUID PRIMARY KEY DEFAULT gen_random_uuid();
ALTER TABLE branch_schedule ALTER COLUMN open_time SET NOT NULL;
ALTER TABLE branch_schedule ALTER COLUMN close_time SET NOT NULL;
ALTER TABLE branch_schedule ADD CONSTRAINT branch_schedule_range CHECK (open_time < close_time);
CREATE INDEX idx_branch_schedule_branch_id ON branch_schedule(branch_id);

ALTER TABLE professional_schedule DROP CONSTRAINT professional_schedule_pkey;
ALTER TABLE professional_schedule DROP COLUMN is_closed;
ALTER TABLE professional_schedule ADD COLUMN id UUID PRIMARY KEY DEFAULT gen_random_uuid();
ALTER TABLE professional_schedule ALTER COLUMN open_time SET NOT NULL;
ALTER TABLE professional_schedule ALTER COLUMN close_time SET NOT NULL;
ALTER TABLE professional_schedule ADD CONSTRAINT professional_schedule_range CHECK (open_time < close_time);
CREATE INDEX idx_professional_schedule_professional_id ON professional_schedule(professional_id);
