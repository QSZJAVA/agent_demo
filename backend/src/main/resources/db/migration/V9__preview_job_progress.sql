ALTER TABLE dispatch_preview_job ADD COLUMN scanned_rows INT NOT NULL DEFAULT 0 AFTER stage;
