CREATE TABLE access (
    id SERIAL PRIMARY KEY,
    date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    token VARCHAR(255) NOT NULL,
    nhs_number VARCHAR(10) NOT NULL,
    success BOOLEAN NOT NULL
);
--;;
CREATE INDEX idx_access_nhs_number_date_time ON access (nhs_number, date_time);
--;;
CREATE INDEX idx_access_token_nhs_number ON access (token, nhs_number);