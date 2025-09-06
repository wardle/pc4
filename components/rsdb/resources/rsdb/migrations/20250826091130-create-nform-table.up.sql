CREATE TABLE t_nform (
    id                    UUID PRIMARY KEY,
    date_time_created     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    encounter_fk          INTEGER REFERENCES t_encounter(id),
    patient_fk            INTEGER REFERENCES t_patient(id),
    is_deleted            BOOLEAN NOT NULL DEFAULT false,
    responsible_user_fk   INTEGER NOT NULL REFERENCES t_user(id),
    form_type             TEXT NOT NULL,
    data                  JSONB
);
--;;