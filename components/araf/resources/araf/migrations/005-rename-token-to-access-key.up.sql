ALTER TABLE request RENAME COLUMN token TO access_key;
--;;
ALTER TABLE access RENAME COLUMN token TO access_key;
--;;
DROP INDEX IF EXISTS idx_access_token_nhs_number;
--;;
CREATE INDEX idx_access_access_key_nhs_number ON access (access_key, nhs_number);