create table if not exists public.hope_agent_consents (
    user_id text primary key,
    enabled boolean not null default false,
    updated_at timestamptz not null default now()
);

alter table public.hope_agent_consents enable row level security;
alter table public.hope_agent_consents force row level security;
revoke all on table public.hope_agent_consents from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_agent_consents to service_role;

create table if not exists public.hope_agent_sessions (
    user_id text not null,
    session_id uuid not null default gen_random_uuid(),
    status text not null default 'active'
        check (status in ('active', 'closed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, session_id)
);

alter table public.hope_agent_sessions enable row level security;
alter table public.hope_agent_sessions force row level security;
revoke all on table public.hope_agent_sessions from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_agent_sessions to service_role;

create table if not exists public.hope_agent_tasks (
    user_id text not null,
    task_id uuid not null default gen_random_uuid(),
    session_id uuid not null,
    idempotency_key uuid not null,
    objective text not null check (length(objective) between 1 and 4000),
    state text not null default 'created'
        check (state in (
            'created', 'understanding', 'planning', 'awaiting_approval', 'ready',
            'executing', 'verifying', 'waiting_for_device', 'paused', 'completed',
            'failed', 'cancelled'
        )),
    plan jsonb not null default '[]'::jsonb check (jsonb_typeof(plan) = 'array'),
    required_permissions jsonb not null default '[]'::jsonb
        check (jsonb_typeof(required_permissions) = 'array'),
    risk_category text not null default 'unknown'
        check (risk_category in ('low', 'moderate', 'high', 'unknown')),
    approval_status text not null default 'not_required'
        check (approval_status in ('not_required', 'pending', 'approved', 'denied', 'expired')),
    verification_status text not null default 'unknown'
        check (verification_status in ('unknown', 'passed', 'failed')),
    cancellation_requested boolean not null default false,
    execution_attempts integer not null default 0 check (execution_attempts between 0 and 20),
    last_event_sequence bigint not null default 0 check (last_event_sequence >= 0),
    lease_expires_at timestamptz,
    failure_code text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    completed_at timestamptz,
    primary key (user_id, task_id),
    unique (user_id, idempotency_key),
    foreign key (user_id, session_id)
        references public.hope_agent_sessions (user_id, session_id)
        on delete cascade
);

alter table public.hope_agent_tasks enable row level security;
alter table public.hope_agent_tasks force row level security;
revoke all on table public.hope_agent_tasks from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_agent_tasks to service_role;

create index if not exists hope_agent_tasks_user_created_idx
    on public.hope_agent_tasks (user_id, created_at desc);
create index if not exists hope_agent_tasks_claim_idx
    on public.hope_agent_tasks (state, lease_expires_at, created_at)
    where state in ('created', 'ready', 'planning', 'understanding');

create or replace function public.enforce_hope_agent_task_capacity()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    active_tasks integer;
    recent_tasks integer;
begin
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(new.user_id, 0)
    );

    select count(*) into active_tasks
    from public.hope_agent_tasks as tasks
    where tasks.user_id = new.user_id
      and tasks.state not in ('completed', 'failed', 'cancelled');

    select count(*) into recent_tasks
    from public.hope_agent_tasks as tasks
    where tasks.user_id = new.user_id
      and tasks.created_at >= now() - interval '24 hours';

    if active_tasks >= 5 or recent_tasks >= 20 then
        raise exception using
            errcode = 'P0001',
            message = 'agent_task_capacity_exceeded';
    end if;

    return new;
end;
$$;

revoke all on function public.enforce_hope_agent_task_capacity()
    from public, anon, authenticated;

drop trigger if exists hope_agent_task_capacity_before_insert
    on public.hope_agent_tasks;
create trigger hope_agent_task_capacity_before_insert
    before insert on public.hope_agent_tasks
    for each row execute function public.enforce_hope_agent_task_capacity();

create table if not exists public.hope_agent_events (
    user_id text not null,
    task_id uuid not null,
    sequence bigint not null check (sequence > 0),
    event_type text not null check (event_type ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    payload jsonb not null default '{}'::jsonb check (jsonb_typeof(payload) = 'object'),
    created_at timestamptz not null default now(),
    primary key (user_id, task_id, sequence),
    foreign key (user_id, task_id)
        references public.hope_agent_tasks (user_id, task_id)
        on delete cascade
);

alter table public.hope_agent_events enable row level security;
alter table public.hope_agent_events force row level security;
revoke all on table public.hope_agent_events from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_agent_events to service_role;

create index if not exists hope_agent_events_created_idx
    on public.hope_agent_events (user_id, task_id, sequence);

create table if not exists public.hope_agent_tool_calls (
    user_id text not null,
    task_id uuid not null,
    tool_call_id uuid not null,
    tool_name text not null check (tool_name ~ '^[a-z][a-z0-9_]{0,63}$'),
    parameters jsonb not null default '{}'::jsonb check (jsonb_typeof(parameters) = 'object'),
    permission_scope text not null check (length(permission_scope) between 1 and 128),
    risk_category text not null check (risk_category in ('low', 'moderate', 'high')),
    status text not null default 'requested'
        check (status in (
            'requested', 'user_confirmation_required', 'executed', 'failed',
            'permission_required', 'unsupported', 'timeout', 'cancelled', 'expired'
        )),
    expires_at timestamptz not null,
    result_idempotency_key uuid,
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, task_id, tool_call_id),
    foreign key (user_id, task_id)
        references public.hope_agent_tasks (user_id, task_id)
        on delete cascade
);

alter table public.hope_agent_tool_calls enable row level security;
alter table public.hope_agent_tool_calls force row level security;
revoke all on table public.hope_agent_tool_calls from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_agent_tool_calls to service_role;

create index if not exists hope_agent_tool_calls_expiry_idx
    on public.hope_agent_tool_calls (expires_at)
    where status = 'requested';

create or replace function public.append_hope_agent_event(
    owner_user_id text,
    target_task_id uuid,
    new_event_type text,
    new_payload jsonb default '{}'::jsonb
)
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    next_sequence bigint;
begin
    update public.hope_agent_tasks as tasks
    set last_event_sequence = tasks.last_event_sequence + 1,
        updated_at = now()
    where tasks.user_id = owner_user_id
      and tasks.task_id = target_task_id
    returning tasks.last_event_sequence into next_sequence;

    if next_sequence is null then
        raise exception 'Agent task not found.';
    end if;

    insert into public.hope_agent_events (
        user_id, task_id, sequence, event_type, payload
    ) values (
        owner_user_id, target_task_id, next_sequence, new_event_type, coalesce(new_payload, '{}'::jsonb)
    );

    return next_sequence;
end;
$$;

revoke all on function public.append_hope_agent_event(text, uuid, text, jsonb)
    from public, anon, authenticated;
grant execute on function public.append_hope_agent_event(text, uuid, text, jsonb)
    to service_role;

create or replace function public.claim_hope_agent_tasks(max_results integer default 5)
returns table (
    user_id text,
    task_id uuid,
    session_id uuid,
    objective text,
    state text,
    execution_attempts integer
)
language sql
volatile
security definer
set search_path = ''
as $$
    with candidates as (
        select tasks.user_id, tasks.task_id
        from public.hope_agent_tasks as tasks
        join public.hope_agent_consents as consents
          on consents.user_id = tasks.user_id and consents.enabled
        where (
            tasks.state in ('created', 'ready')
            or (tasks.state in ('understanding', 'planning') and tasks.lease_expires_at < now())
        )
          and not tasks.cancellation_requested
          and tasks.execution_attempts < 20
        order by tasks.created_at
        for update of tasks skip locked
        limit least(greatest(coalesce(max_results, 5), 1), 20)
    )
    update public.hope_agent_tasks as tasks
    set state = 'planning',
        execution_attempts = tasks.execution_attempts + 1,
        lease_expires_at = now() + interval '5 minutes',
        updated_at = now()
    from candidates
    where tasks.user_id = candidates.user_id
      and tasks.task_id = candidates.task_id
    returning tasks.user_id, tasks.task_id, tasks.session_id,
              tasks.objective, tasks.state, tasks.execution_attempts;
$$;

revoke all on function public.claim_hope_agent_tasks(integer)
    from public, anon, authenticated;
grant execute on function public.claim_hope_agent_tasks(integer)
    to service_role;

create table if not exists public.hope_agent_request_limits (
    user_id text not null,
    bucket_start timestamptz not null,
    request_count integer not null check (request_count > 0),
    primary key (user_id, bucket_start)
);

alter table public.hope_agent_request_limits enable row level security;
alter table public.hope_agent_request_limits force row level security;
revoke all on table public.hope_agent_request_limits from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_agent_request_limits to service_role;

create or replace function public.consume_hope_agent_gateway_quota(
    owner_user_id text,
    max_requests integer default 120
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    active_bucket timestamptz := date_trunc('minute', now());
    request_total integer;
begin
    delete from public.hope_agent_request_limits as limits
    where limits.user_id = owner_user_id
      and limits.bucket_start < active_bucket - interval '2 minutes';

    insert into public.hope_agent_request_limits as current_limit
        (user_id, bucket_start, request_count)
    values (owner_user_id, active_bucket, 1)
    on conflict (user_id, bucket_start) do update
      set request_count = least(current_limit.request_count + 1, 1000000)
    returning request_count into request_total;

    return request_total <= least(greatest(coalesce(max_requests, 120), 1), 300);
end;
$$;

revoke all on function public.consume_hope_agent_gateway_quota(text, integer)
    from public, anon, authenticated;
grant execute on function public.consume_hope_agent_gateway_quota(text, integer)
    to service_role;
