CREATE TABLE request (
    id SERIAL PRIMARY KEY,
    date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    araf_type TEXT NOT NULL,
    nhs_number TEXT NOT NULL,
    access_key TEXT NOT NULL,
    long_access_key TEXT NOT NULL UNIQUE,
    expires TIMESTAMP WITH TIME ZONE NOT NULL,
    data JSONB
);
--;;
CREATE TABLE response (
    id SERIAL PRIMARY KEY,
    request_id INTEGER NOT NULL REFERENCES request(id) ON DELETE RESTRICT ON UPDATE CASCADE,
    date_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    data JSONB,
    signature BYTEA,
    mime_type VARCHAR(255),
    name TEXT
);
--;;
CREATE TABLE access (
    id SERIAL PRIMARY KEY,
    date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    nhs_number TEXT NOT NULL,
    access_key TEXT,
    long_access_key TEXT,
    success BOOLEAN NOT NULL
);
--;;
CREATE INDEX idx_request_access_key_nhs_number ON request (access_key, nhs_number);

--;;
CREATE INDEX idx_access_nhs_number_date_time ON access (nhs_number, date_time);