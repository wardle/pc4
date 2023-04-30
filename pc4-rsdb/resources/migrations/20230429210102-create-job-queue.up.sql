CREATE TABLE t_job_queue (
    id int not null primary key generated always as identity,
    topic text not null,
    created timestamptz default now(),
    payload	text
);
--;;
CREATE INDEX t_job_queue_topic_idx on t_job_queue(topic);
--;;
CREATE INDEX t_job_queue_created_time_ids on t_job_queue(created)
