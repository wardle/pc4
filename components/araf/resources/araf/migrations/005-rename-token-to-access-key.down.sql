DROP INDEX IF EXISTS idx_access_access_key_nhs_number;
--;;
ALTER TABLE access RENAME COLUMN access_key TO token;
--;;
ALTER TABLE request RENAME COLUMN access_key TO token;
--;;
CREATE INDEX idx_access_token_nhs_number ON access (token, nhs_number);