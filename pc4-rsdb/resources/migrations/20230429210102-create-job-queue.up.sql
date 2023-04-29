CREATE TABLE t_job_queue (
    id int not null primary key generated always as identity,
    topic text not null,
    queue_time	timestamptz default now(),
    payload	text
);