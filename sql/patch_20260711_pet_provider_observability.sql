-- Pet provider failure observability only. No provider flag, queue, billing, or car behavior changes.
alter table task add column if not exists provider_http_status int null;
alter table task add column if not exists provider_error_code varchar(120) null;
alter table task add column if not exists provider_error_message text null;
alter table task add column if not exists provider_response_raw longtext null;
alter table task add column if not exists provider_trace_id varchar(120) null;
alter table task add column if not exists provider_request_id varchar(120) null;
alter table task add column if not exists provider_task_id varchar(120) null;
alter table task add column if not exists provider_duration_ms bigint null;
alter table task add column if not exists provider_stack_trace text null;
