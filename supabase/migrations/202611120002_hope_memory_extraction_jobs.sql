create table if not exists public.hope_memory_extraction_jobs (
    user_id text not null,
    turn_id uuid not null,
    status text not null default 'queued'
        check (status in ('queued', 'processing', 'pending', 'retry_wait', 'succeeded', 'failed', 'cancelled')),
    attempts integer not null default 0 check (attempts between 0 and 5),
    mem0_event_id text,
    last_error_category text,
    next_attempt_at timestamptz not null default now(),
    lease_expires_at timestamptz,
    last_attempt_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    processed_at timestamptz,
    primary key (user_id, turn_id),
    foreign key (user_id, turn_id)
        references public.hope_conversation_turns (user_id, turn_id)
        on delete cascade
);

alter table public.hope_memory_extraction_jobs enable row level security;
alter table public.hope_memory_extraction_jobs force row level security;
revoke all on table public.hope_memory_extraction_jobs from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_memory_extraction_jobs to service_role;

create index if not exists hope_memory_jobs_due_idx
    on public.hope_memory_extraction_jobs (user_id, next_attempt_at)
    where status in ('queued', 'pending', 'retry_wait', 'processing');

create table if not exists public.hope_memory_user_diagnostics (
    user_id text primary key,
    last_retrieval_at timestamptz,
    last_mem0_result_count integer not null default 0 check (last_mem0_result_count >= 0),
    last_transcript_result_count integer not null default 0 check (last_transcript_result_count >= 0),
    last_delete_event_id text,
    last_delete_status text not null default 'none'
        check (last_delete_status in ('none', 'pending', 'succeeded', 'failed')),
    updated_at timestamptz not null default now()
);

alter table public.hope_memory_user_diagnostics enable row level security;
alter table public.hope_memory_user_diagnostics force row level security;
revoke all on table public.hope_memory_user_diagnostics from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_memory_user_diagnostics to service_role;

create or replace function public.claim_hope_memory_extraction_jobs(
    owner_user_id text,
    max_results integer default 10
)
returns table (
    user_id text,
    turn_id uuid,
    status text,
    attempts integer,
    mem0_event_id text
)
language sql
volatile
security definer
set search_path = ''
as $$
    with candidates as (
        select jobs.user_id, jobs.turn_id
        from public.hope_memory_extraction_jobs as jobs
        where jobs.user_id = owner_user_id
          and (
              (jobs.status in ('queued', 'pending', 'retry_wait') and jobs.next_attempt_at <= now())
              or (jobs.status = 'processing' and jobs.lease_expires_at < now())
          )
        order by jobs.next_attempt_at, jobs.created_at
        for update skip locked
        limit least(greatest(coalesce(max_results, 10), 1), 20)
    )
    update public.hope_memory_extraction_jobs as jobs
    set status = 'processing',
        lease_expires_at = now() + interval '2 minutes',
        updated_at = now()
    from candidates
    where jobs.user_id = candidates.user_id
      and jobs.turn_id = candidates.turn_id
    returning jobs.user_id, jobs.turn_id, jobs.status, jobs.attempts, jobs.mem0_event_id;
$$;

revoke all on function public.claim_hope_memory_extraction_jobs(text, integer)
    from public, anon, authenticated;
grant execute on function public.claim_hope_memory_extraction_jobs(text, integer)
    to service_role;
