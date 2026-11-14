alter table public.hope_conversation_turns
    add column if not exists source_turn_id text,
    add column if not exists source_user_message_id text,
    add column if not exists source_assistant_message_id text,
    add column if not exists source_user_created_at timestamptz,
    add column if not exists source_assistant_created_at timestamptz,
    add column if not exists source_user_timestamp_origin text,
    add column if not exists source_assistant_timestamp_origin text,
    add column if not exists source_turn_order integer not null default 0,
    add column if not exists source_conversation_title text;

update public.hope_conversation_turns
set source_user_timestamp_origin = coalesce(source_user_timestamp_origin, source_timestamp_origin),
    source_assistant_timestamp_origin = coalesce(source_assistant_timestamp_origin, source_timestamp_origin);

alter table public.hope_conversation_turns
    alter column source_user_timestamp_origin set default 'server_ingested',
    alter column source_user_timestamp_origin set not null,
    alter column source_assistant_timestamp_origin set default 'server_ingested',
    alter column source_assistant_timestamp_origin set not null,
    drop constraint if exists hope_conversation_turns_timestamp_origin_check,
    add constraint hope_conversation_turns_timestamp_origin_check
        check (source_timestamp_origin in ('client_recorded', 'server_ingested', 'imported_unverified')),
    drop constraint if exists hope_conversation_turns_source_user_timestamp_origin_check,
    add constraint hope_conversation_turns_source_user_timestamp_origin_check
        check (source_user_timestamp_origin in ('client_recorded', 'server_ingested', 'imported_unverified')),
    drop constraint if exists hope_conversation_turns_source_assistant_timestamp_origin_check,
    add constraint hope_conversation_turns_source_assistant_timestamp_origin_check
        check (source_assistant_timestamp_origin in ('client_recorded', 'server_ingested', 'imported_unverified')),
    drop constraint if exists hope_conversation_turns_source_turn_order_check,
    add constraint hope_conversation_turns_source_turn_order_check
        check (source_turn_order >= 0),
    drop constraint if exists hope_conversation_turns_source_ids_check,
    add constraint hope_conversation_turns_source_ids_check
        check (
            (source_turn_id is null or length(source_turn_id) between 1 and 200)
            and (source_user_message_id is null or length(source_user_message_id) between 1 and 200)
            and (source_assistant_message_id is null or length(source_assistant_message_id) between 1 and 200)
            and (source_conversation_title is null or length(source_conversation_title) <= 500)
        );

create table if not exists public.hope_conversation_messages (
    user_id text not null,
    message_id uuid not null default gen_random_uuid(),
    turn_id uuid not null,
    conversation_id text,
    source_turn_id text,
    source_message_id text,
    message_order integer not null check (message_order >= 0),
    role text not null check (role in ('user', 'assistant', 'system', 'tool')),
    content text not null check (length(content) between 1 and 20000),
    original_timestamp timestamptz not null,
    timestamp_origin text not null check (timestamp_origin in ('client_recorded', 'server_ingested', 'imported_unverified')),
    created_at timestamptz not null default now(),
    primary key (user_id, message_id),
    unique (user_id, turn_id, message_order),
    foreign key (user_id, turn_id)
        references public.hope_conversation_turns (user_id, turn_id)
        on delete cascade,
    check (conversation_id is null or length(conversation_id) between 1 and 160),
    check (source_turn_id is null or length(source_turn_id) between 1 and 200),
    check (source_message_id is null or length(source_message_id) between 1 and 200)
);

create unique index if not exists hope_conversation_messages_source_id_idx
    on public.hope_conversation_messages (user_id, conversation_id, source_message_id)
    where conversation_id is not null and source_message_id is not null;

create index if not exists hope_conversation_messages_source_order_idx
    on public.hope_conversation_messages (user_id, conversation_id, message_order);

create index if not exists hope_conversation_messages_search_idx
    on public.hope_conversation_messages using gin (
        to_tsvector('simple', content)
    );

insert into public.hope_conversation_messages (
    user_id,
    turn_id,
    conversation_id,
    source_turn_id,
    source_message_id,
    message_order,
    role,
    content,
    original_timestamp,
    timestamp_origin
)
select
    turns.user_id,
    turns.turn_id,
    turns.source_conversation_id,
    turns.source_turn_id,
    turns.source_user_message_id,
    0,
    'user',
    turns.user_text,
    coalesce(turns.source_user_created_at, turns.source_created_at),
    turns.source_user_timestamp_origin
from public.hope_conversation_turns as turns
where length(turns.user_text) between 1 and 20000
on conflict (user_id, turn_id, message_order) do nothing;

insert into public.hope_conversation_messages (
    user_id,
    turn_id,
    conversation_id,
    source_turn_id,
    source_message_id,
    message_order,
    role,
    content,
    original_timestamp,
    timestamp_origin
)
select
    turns.user_id,
    turns.turn_id,
    turns.source_conversation_id,
    turns.source_turn_id,
    turns.source_assistant_message_id,
    1,
    'assistant',
    turns.assistant_text,
    coalesce(turns.source_assistant_created_at, turns.source_created_at),
    turns.source_assistant_timestamp_origin
from public.hope_conversation_turns as turns
where length(turns.assistant_text) between 1 and 20000
on conflict (user_id, turn_id, message_order) do nothing;

create table if not exists public.hope_memory_projects (
    user_id text not null,
    project_id uuid not null default gen_random_uuid(),
    name text not null check (length(name) between 1 and 160),
    normalized_name text not null check (length(normalized_name) between 1 and 160),
    summary text not null default '' check (length(summary) <= 8000),
    technology_stack jsonb not null default '[]'::jsonb
        check (jsonb_typeof(technology_stack) = 'array'),
    repository_url text,
    project_status text not null default 'active'
        check (project_status in ('active', 'paused', 'completed', 'archived')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, project_id),
    unique (user_id, normalized_name),
    check (repository_url is null or length(repository_url) <= 2000)
);

create table if not exists public.hope_memory_facts (
    user_id text not null,
    memory_id uuid not null default gen_random_uuid(),
    memory_type text not null
        check (memory_type in ('episodic', 'semantic', 'procedural', 'project', 'conversational')),
    content text not null check (length(content) between 1 and 20000),
    entity_name text,
    attribute text,
    value text,
    source_type text not null
        check (source_type in ('user_explicit', 'assistant_inference', 'ai_suggestion', 'external_verified', 'user_correction')),
    source_turn_id uuid,
    source_conversation_id text,
    source_message_id text,
    source_created_at timestamptz,
    extracted_at timestamptz not null default now(),
    confidence numeric(4, 3) not null check (confidence between 0 and 1),
    verification_status text not null default 'needs_review'
        check (verification_status in ('needs_review', 'source_verified', 'user_confirmed', 'externally_verified', 'superseded')),
    project_id uuid,
    identity_key text,
    version integer not null default 1 check (version >= 1),
    supersedes_memory_id uuid,
    superseded_by_memory_id uuid,
    is_current boolean not null default true,
    retention_policy text not null default 'user_controlled'
        check (retention_policy in ('user_controlled', 'retain_until_deleted', 'expires')),
    model_version text,
    search_document tsvector generated always as (
        to_tsvector(
            'simple',
            coalesce(content, '') || ' ' || coalesce(entity_name, '') || ' ' ||
                coalesce(attribute, '') || ' ' || coalesce(value, '')
        )
    ) stored,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, memory_id),
    foreign key (user_id, source_turn_id)
        references public.hope_conversation_turns (user_id, turn_id)
        on delete cascade,
    foreign key (user_id, project_id)
        references public.hope_memory_projects (user_id, project_id)
        on delete restrict,
    check (identity_key is null or length(identity_key) between 1 and 300),
    check (entity_name is null or length(entity_name) <= 300),
    check (attribute is null or length(attribute) <= 300),
    check (value is null or length(value) <= 8000),
    check (source_conversation_id is null or length(source_conversation_id) <= 160),
    check (source_message_id is null or length(source_message_id) <= 200),
    check (model_version is null or length(model_version) <= 100)
);

create unique index if not exists hope_memory_facts_current_identity_idx
    on public.hope_memory_facts (user_id, identity_key)
    where is_current and identity_key is not null;

create index if not exists hope_memory_facts_search_idx
    on public.hope_memory_facts using gin (search_document);

create index if not exists hope_memory_facts_type_time_idx
    on public.hope_memory_facts (user_id, memory_type, source_created_at desc);

create index if not exists hope_memory_facts_project_idx
    on public.hope_memory_facts (user_id, project_id, is_current);

create table if not exists public.hope_memory_entities (
    user_id text not null,
    entity_id uuid not null default gen_random_uuid(),
    entity_type text not null
        check (entity_type in ('person', 'project', 'organization', 'technology', 'preference', 'task', 'event', 'decision', 'repository', 'integration', 'other')),
    name text not null check (length(name) between 1 and 300),
    normalized_name text not null check (length(normalized_name) between 1 and 300),
    properties jsonb not null default '{}'::jsonb check (jsonb_typeof(properties) = 'object'),
    project_id uuid,
    source_turn_id uuid,
    source_conversation_id text,
    source_message_id text,
    confidence numeric(4, 3) not null default 0.5 check (confidence between 0 and 1),
    verification_status text not null default 'needs_review'
        check (verification_status in ('needs_review', 'source_verified', 'user_confirmed', 'externally_verified')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, entity_id),
    unique (user_id, entity_type, normalized_name),
    foreign key (user_id, project_id)
        references public.hope_memory_projects (user_id, project_id)
        on delete restrict,
    foreign key (user_id, source_turn_id)
        references public.hope_conversation_turns (user_id, turn_id)
        on delete cascade,
    check (source_conversation_id is null or length(source_conversation_id) <= 160),
    check (source_message_id is null or length(source_message_id) <= 200)
);

create index if not exists hope_memory_entities_name_idx
    on public.hope_memory_entities (user_id, normalized_name);

create table if not exists public.hope_memory_relationships (
    user_id text not null,
    relationship_id uuid not null default gen_random_uuid(),
    source_entity_id uuid not null,
    target_entity_id uuid not null,
    relationship text not null check (length(relationship) between 1 and 100),
    source_turn_id uuid,
    confidence numeric(4, 3) not null default 0.5 check (confidence between 0 and 1),
    verification_status text not null default 'needs_review'
        check (verification_status in ('needs_review', 'source_verified', 'user_confirmed', 'externally_verified')),
    created_at timestamptz not null default now(),
    primary key (user_id, relationship_id),
    unique (user_id, source_entity_id, target_entity_id, relationship, source_turn_id),
    foreign key (user_id, source_entity_id)
        references public.hope_memory_entities (user_id, entity_id)
        on delete cascade,
    foreign key (user_id, target_entity_id)
        references public.hope_memory_entities (user_id, entity_id)
        on delete cascade,
    foreign key (user_id, source_turn_id)
        references public.hope_conversation_turns (user_id, turn_id)
        on delete cascade
);

create table if not exists public.hope_memory_summaries (
    user_id text not null,
    summary_id uuid not null default gen_random_uuid(),
    conversation_id text not null check (length(conversation_id) between 1 and 160),
    summary text not null check (length(summary) between 1 and 12000),
    topic_labels jsonb not null default '[]'::jsonb check (jsonb_typeof(topic_labels) = 'array'),
    source_turn_ids uuid[] not null default '{}',
    version integer not null default 1 check (version >= 1),
    model_version text,
    created_at timestamptz not null default now(),
    primary key (user_id, summary_id),
    unique (user_id, conversation_id, version)
);

create table if not exists public.hope_memory_consolidation_jobs (
    user_id text not null,
    turn_id uuid not null,
    status text not null default 'queued'
        check (status in ('queued', 'processing', 'retry_wait', 'succeeded', 'failed', 'cancelled')),
    attempts integer not null default 0 check (attempts between 0 and 5),
    last_error_category text,
    next_attempt_at timestamptz not null default now(),
    lease_expires_at timestamptz,
    last_attempt_at timestamptz,
    processed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, turn_id),
    foreign key (user_id, turn_id)
        references public.hope_conversation_turns (user_id, turn_id)
        on delete cascade
);

create index if not exists hope_memory_consolidation_jobs_due_idx
    on public.hope_memory_consolidation_jobs (user_id, next_attempt_at)
    where status in ('queued', 'retry_wait', 'processing');

create table if not exists public.hope_memory_import_runs (
    user_id text not null,
    import_id uuid not null,
    archive_sha256 text not null check (archive_sha256 ~ '^[0-9a-f]{64}$'),
    status text not null default 'processing'
        check (status in ('processing', 'completed', 'failed', 'cancelled')),
    expected_batches integer not null check (expected_batches between 1 and 100000),
    completed_batches integer not null default 0 check (completed_batches >= 0),
    imported_turns integer not null default 0 check (imported_turns >= 0),
    rejected_turns integer not null default 0 check (rejected_turns >= 0),
    last_error_category text,
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    updated_at timestamptz not null default now(),
    primary key (user_id, import_id),
    check (completed_batches <= expected_batches)
);

create unique index if not exists hope_memory_import_runs_archive_idx
    on public.hope_memory_import_runs (user_id, archive_sha256);

create table if not exists public.hope_memory_import_batches (
    user_id text not null,
    import_id uuid not null,
    batch_index integer not null check (batch_index >= 0),
    imported_turns integer not null default 0 check (imported_turns >= 0),
    rejected_turns integer not null default 0 check (rejected_turns >= 0),
    created_at timestamptz not null default now(),
    primary key (user_id, import_id, batch_index),
    foreign key (user_id, import_id)
        references public.hope_memory_import_runs (user_id, import_id)
        on delete cascade
);

create table if not exists public.hope_memory_export_runs (
    user_id text not null,
    export_id uuid not null,
    archive_sha256 text not null check (archive_sha256 ~ '^[0-9a-f]{64}$'),
    record_counts jsonb not null check (jsonb_typeof(record_counts) = 'object'),
    verified boolean not null default false,
    created_at timestamptz not null default now(),
    primary key (user_id, export_id)
);

create table if not exists public.hope_memory_audit_events (
    user_id text not null,
    event_id uuid not null default gen_random_uuid(),
    action text not null check (action in ('fact_corrected', 'fact_deleted', 'memory_exported', 'history_imported', 'history_deleted', 'project_updated')),
    memory_id uuid,
    import_id uuid,
    export_id uuid,
    details jsonb not null default '{}'::jsonb check (jsonb_typeof(details) = 'object'),
    created_at timestamptz not null default now(),
    primary key (user_id, event_id)
);

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'hope_conversation_messages',
        'hope_memory_projects',
        'hope_memory_facts',
        'hope_memory_entities',
        'hope_memory_relationships',
        'hope_memory_summaries',
        'hope_memory_consolidation_jobs',
        'hope_memory_import_runs',
        'hope_memory_import_batches',
        'hope_memory_export_runs',
        'hope_memory_audit_events'
    ]
    loop
        execute format('alter table public.%I enable row level security', table_name);
        execute format('alter table public.%I force row level security', table_name);
        execute format('revoke all on table public.%I from public, anon, authenticated', table_name);
        execute format('grant all on table public.%I to service_role', table_name);
    end loop;
end
$$;

drop function if exists public.search_hope_conversation_messages(
    text, text, integer, integer, text, timestamptz, timestamptz
);

create or replace function public.search_hope_conversation_messages(
    search_query text,
    owner_user_id text,
    max_results integer default 10,
    result_offset integer default 0,
    filter_conversation_id text default null,
    from_timestamp timestamptz default null,
    to_timestamp timestamptz default null,
    sort_order text default 'relevance'
)
returns table (
    turn_id uuid,
    conversation_id text,
    source_turn_id text,
    source_message_id text,
    message_order integer,
    role text,
    content text,
    original_timestamp timestamptz,
    timestamp_origin text
)
language sql
stable
security invoker
set search_path = ''
as $$
    select
        messages.turn_id,
        messages.conversation_id,
        messages.source_turn_id,
        messages.source_message_id,
        messages.message_order,
        messages.role,
        messages.content,
        messages.original_timestamp,
        messages.timestamp_origin
    from public.hope_conversation_messages as messages
    where messages.user_id = owner_user_id
      and search_query is not null
      and length(search_query) between 1 and 2000
      and to_tsvector('simple', messages.content) @@ websearch_to_tsquery('simple', search_query)
      and (filter_conversation_id is null or messages.conversation_id = filter_conversation_id)
      and (from_timestamp is null or messages.original_timestamp >= from_timestamp)
      and (to_timestamp is null or messages.original_timestamp <= to_timestamp)
    order by
        case when sort_order = 'oldest' then messages.original_timestamp end asc nulls last,
        case when sort_order = 'newest' then messages.original_timestamp end desc nulls last,
        case when sort_order not in ('oldest', 'newest') then ts_rank(
            to_tsvector('simple', messages.content),
            websearch_to_tsquery('simple', search_query)
        ) end desc nulls last,
        messages.original_timestamp desc
    limit least(greatest(coalesce(max_results, 10), 1), 50)
    offset least(greatest(coalesce(result_offset, 0), 0), 100000);
$$;

create or replace function public.claim_hope_memory_consolidation_jobs(
    owner_user_id text,
    max_results integer default 10
)
returns table (user_id text, turn_id uuid, attempts integer)
language sql
volatile
security definer
set search_path = ''
as $$
    with candidates as (
        select jobs.user_id, jobs.turn_id
        from public.hope_memory_consolidation_jobs as jobs
        where jobs.user_id = owner_user_id
          and (
              (jobs.status in ('queued', 'retry_wait') and jobs.next_attempt_at <= now())
              or (jobs.status = 'processing' and jobs.lease_expires_at < now())
          )
        order by jobs.next_attempt_at, jobs.created_at
        for update skip locked
        limit least(greatest(coalesce(max_results, 10), 1), 20)
    )
    update public.hope_memory_consolidation_jobs as jobs
    set status = 'processing',
        attempts = jobs.attempts + 1,
        last_attempt_at = now(),
        lease_expires_at = now() + interval '2 minutes',
        updated_at = now()
    from candidates
    where jobs.user_id = candidates.user_id
      and jobs.turn_id = candidates.turn_id
    returning jobs.user_id, jobs.turn_id, jobs.attempts;
$$;

create or replace function public.save_hope_memory_fact(
    owner_user_id text,
    new_memory_type text,
    new_content text,
    new_entity_name text,
    new_attribute text,
    new_value text,
    new_source_type text,
    new_source_turn_id uuid,
    new_source_conversation_id text,
    new_source_message_id text,
    new_source_created_at timestamptz,
    new_confidence numeric,
    new_project_id uuid,
    new_identity_key text,
    new_model_version text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    old_fact public.hope_memory_facts%rowtype;
    next_memory_id uuid := gen_random_uuid();
    next_version integer := 1;
begin
    if new_content is null or length(trim(new_content)) not between 1 and 20000
       or new_identity_key is null or length(new_identity_key) not between 1 and 300 then
        raise exception using errcode = '22023', message = 'memory_fact_invalid';
    end if;

    select * into old_fact
    from public.hope_memory_facts as facts
    where facts.user_id = owner_user_id
      and facts.identity_key = new_identity_key
      and facts.is_current
    for update;

    if found then
        if old_fact.memory_type = new_memory_type
           and old_fact.content = trim(new_content)
           and old_fact.value is not distinct from new_value then
            return jsonb_build_object(
                'memory_id', old_fact.memory_id,
                'duplicate', true,
                'conflict', false,
                'version', old_fact.version
            );
        end if;
        next_version := old_fact.version + 1;
        update public.hope_memory_facts as facts
        set is_current = false,
            verification_status = 'superseded',
            updated_at = now()
        where facts.user_id = owner_user_id
          and facts.memory_id = old_fact.memory_id;
    else
        select coalesce(max(facts.version), 0) + 1 into next_version
        from public.hope_memory_facts as facts
        where facts.user_id = owner_user_id
          and facts.identity_key = new_identity_key;
        old_fact.memory_id := null;
    end if;

    insert into public.hope_memory_facts (
        user_id, memory_id, memory_type, content, entity_name, attribute, value,
        source_type, source_turn_id, source_conversation_id, source_message_id,
        source_created_at, extracted_at, confidence, verification_status,
        project_id, identity_key, version, supersedes_memory_id, is_current,
        retention_policy, model_version
    ) values (
        owner_user_id, next_memory_id, new_memory_type, trim(new_content),
        new_entity_name, new_attribute, new_value, new_source_type,
        new_source_turn_id, new_source_conversation_id, new_source_message_id,
        new_source_created_at, now(), new_confidence, 'needs_review',
        new_project_id, new_identity_key, next_version, old_fact.memory_id,
        true, 'user_controlled', new_model_version
    );

    if old_fact.memory_id is not null then
        update public.hope_memory_facts as facts
        set superseded_by_memory_id = next_memory_id,
            updated_at = now()
        where facts.user_id = owner_user_id
          and facts.memory_id = old_fact.memory_id;
    end if;

    return jsonb_build_object(
        'memory_id', next_memory_id,
        'duplicate', false,
        'conflict', old_fact.memory_id is not null,
        'version', next_version
    );
end;
$$;

drop function if exists public.search_hope_memory_facts(
    text, text, integer, integer, uuid, text, timestamptz, timestamptz
);

create or replace function public.search_hope_memory_facts(
    search_query text,
    owner_user_id text,
    max_results integer default 10,
    result_offset integer default 0,
    filter_project_id uuid default null,
    filter_memory_type text default null,
    from_timestamp timestamptz default null,
    to_timestamp timestamptz default null,
    sort_order text default 'relevance'
)
returns table (
    memory_id uuid,
    memory_type text,
    content text,
    entity_name text,
    attribute text,
    value text,
    source_type text,
    source_turn_id uuid,
    source_conversation_id text,
    source_message_id text,
    source_created_at timestamptz,
    confidence numeric,
    verification_status text,
    project_id uuid,
    identity_key text,
    version integer
)
language sql
stable
security invoker
set search_path = ''
as $$
    select
        facts.memory_id,
        facts.memory_type,
        facts.content,
        facts.entity_name,
        facts.attribute,
        facts.value,
        facts.source_type,
        facts.source_turn_id,
        facts.source_conversation_id,
        facts.source_message_id,
        facts.source_created_at,
        facts.confidence,
        facts.verification_status,
        facts.project_id,
        facts.identity_key,
        facts.version
    from public.hope_memory_facts as facts
    where facts.user_id = owner_user_id
      and facts.is_current
      and (search_query is null or (
          length(search_query) between 1 and 2000
          and facts.search_document @@ websearch_to_tsquery('simple', search_query)
      ))
      and (filter_project_id is null or facts.project_id = filter_project_id)
      and (filter_memory_type is null or facts.memory_type = filter_memory_type)
      and (from_timestamp is null or facts.source_created_at >= from_timestamp)
      and (to_timestamp is null or facts.source_created_at <= to_timestamp)
    order by
        case when sort_order = 'oldest' then facts.source_created_at end asc nulls last,
        case when sort_order = 'newest' then facts.source_created_at end desc nulls last,
        case when sort_order not in ('oldest', 'newest') and search_query is not null
            then ts_rank(facts.search_document, websearch_to_tsquery('simple', search_query))
        end desc nulls last,
        facts.source_created_at desc nulls last,
        facts.created_at desc
    limit least(greatest(coalesce(max_results, 10), 1), 50)
    offset least(greatest(coalesce(result_offset, 0), 0), 100000);
$$;

create or replace function public.correct_hope_memory_fact(
    owner_user_id text,
    target_memory_id uuid,
    corrected_content text,
    corrected_value text default null
)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    old_fact public.hope_memory_facts%rowtype;
    new_memory_id uuid := gen_random_uuid();
begin
    if corrected_content is null or length(trim(corrected_content)) not between 1 and 20000 then
        raise exception using errcode = '22023', message = 'memory_correction_invalid';
    end if;

    select * into old_fact
    from public.hope_memory_facts as facts
    where facts.user_id = owner_user_id
      and facts.memory_id = target_memory_id
      and facts.is_current
    for update;
    if not found then
        raise exception using errcode = 'P0002', message = 'memory_not_found';
    end if;

    update public.hope_memory_facts
    set is_current = false,
        verification_status = 'superseded',
        superseded_by_memory_id = new_memory_id,
        updated_at = now()
    where user_id = owner_user_id
      and memory_id = target_memory_id;

    insert into public.hope_memory_facts (
        user_id, memory_id, memory_type, content, entity_name, attribute, value,
        source_type, source_turn_id, source_conversation_id, source_message_id,
        source_created_at, extracted_at, confidence, verification_status,
        project_id, identity_key, version, supersedes_memory_id, is_current,
        retention_policy, model_version
    ) values (
        owner_user_id, new_memory_id, old_fact.memory_type, trim(corrected_content),
        old_fact.entity_name, old_fact.attribute, coalesce(corrected_value, old_fact.value),
        'user_correction', old_fact.source_turn_id, old_fact.source_conversation_id,
        old_fact.source_message_id, old_fact.source_created_at, now(), 1.0,
        'user_confirmed', old_fact.project_id, old_fact.identity_key,
        old_fact.version + 1, target_memory_id, true, old_fact.retention_policy,
        old_fact.model_version
    );

    insert into public.hope_memory_audit_events (user_id, action, memory_id, details)
    values (
        owner_user_id,
        'fact_corrected',
        new_memory_id,
        jsonb_build_object('previousMemoryId', target_memory_id, 'version', old_fact.version + 1)
    );
    return new_memory_id;
end;
$$;

create or replace function public.delete_hope_memory_fact(
    owner_user_id text,
    target_memory_id uuid
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    target_identity_key text;
    deleted_count integer;
begin
    select facts.identity_key into target_identity_key
    from public.hope_memory_facts as facts
    where facts.user_id = owner_user_id
      and facts.memory_id = target_memory_id;
    if not found then return false; end if;

    delete from public.hope_memory_facts as facts
    where facts.user_id = owner_user_id
      and (
          facts.memory_id = target_memory_id
          or (target_identity_key is not null and facts.identity_key = target_identity_key)
      );
    get diagnostics deleted_count = row_count;

    insert into public.hope_memory_audit_events (user_id, action, memory_id, details)
    values (
        owner_user_id,
        'fact_deleted',
        target_memory_id,
        jsonb_build_object('versionsDeleted', deleted_count)
    );
    return deleted_count > 0;
end;
$$;

create or replace function public.record_hope_memory_import_batch(
    owner_user_id text,
    target_import_id uuid,
    target_batch_index integer,
    imported_count integer,
    rejected_count integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    import_run public.hope_memory_import_runs%rowtype;
    batch_count integer;
    total_imported integer;
    total_rejected integer;
begin
    select * into import_run
    from public.hope_memory_import_runs as runs
    where runs.user_id = owner_user_id
      and runs.import_id = target_import_id
    for update;
    if not found or import_run.status <> 'processing' then
        raise exception using errcode = 'P0002', message = 'memory_import_not_active';
    end if;
    if target_batch_index < 0 or target_batch_index >= import_run.expected_batches
       or target_batch_index > import_run.completed_batches
       or imported_count < 0 or rejected_count < 0 then
        raise exception using errcode = '22023', message = 'memory_import_batch_invalid';
    end if;

    insert into public.hope_memory_import_batches (
        user_id, import_id, batch_index, imported_turns, rejected_turns
    ) values (
        owner_user_id, target_import_id, target_batch_index, imported_count, rejected_count
    )
    on conflict (user_id, import_id, batch_index) do update
    set imported_turns = excluded.imported_turns,
        rejected_turns = excluded.rejected_turns,
        created_at = now();

    select count(*), coalesce(sum(batches.imported_turns), 0),
        coalesce(sum(batches.rejected_turns), 0)
    into batch_count, total_imported, total_rejected
    from public.hope_memory_import_batches as batches
    where batches.user_id = owner_user_id
      and batches.import_id = target_import_id;

    update public.hope_memory_import_runs as runs
    set completed_batches = batch_count,
        imported_turns = total_imported,
        rejected_turns = total_rejected,
        updated_at = now()
    where runs.user_id = owner_user_id
      and runs.import_id = target_import_id;

    return jsonb_build_object(
        'completedBatches', batch_count,
        'expectedBatches', import_run.expected_batches,
        'importedTurns', total_imported,
        'rejectedTurns', total_rejected
    );
end;
$$;

create or replace function public.complete_hope_memory_import(
    owner_user_id text,
    target_import_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    import_run public.hope_memory_import_runs%rowtype;
    batch_count integer;
begin
    select * into import_run
    from public.hope_memory_import_runs as runs
    where runs.user_id = owner_user_id
      and runs.import_id = target_import_id
    for update;
    if not found then
        raise exception using errcode = 'P0002', message = 'memory_import_not_found';
    end if;
    if import_run.status = 'completed' then
        return jsonb_build_object(
            'completed', true,
            'completedBatches', import_run.completed_batches,
            'expectedBatches', import_run.expected_batches,
            'importedTurns', import_run.imported_turns,
            'rejectedTurns', import_run.rejected_turns
        );
    end if;
    if import_run.status <> 'processing' then
        raise exception using errcode = '22023', message = 'memory_import_not_active';
    end if;

    select count(*) into batch_count
    from public.hope_memory_import_batches as batches
    where batches.user_id = owner_user_id
      and batches.import_id = target_import_id
      and batches.batch_index >= 0
      and batches.batch_index < import_run.expected_batches;
    if batch_count <> import_run.expected_batches then
        raise exception using errcode = '22023', message = 'memory_import_batches_incomplete';
    end if;

    update public.hope_memory_import_runs as runs
    set status = 'completed',
        completed_at = now(),
        updated_at = now()
    where runs.user_id = owner_user_id
      and runs.import_id = target_import_id;

    insert into public.hope_memory_audit_events (user_id, action, import_id, details)
    values (
        owner_user_id,
        'history_imported',
        target_import_id,
        jsonb_build_object(
            'importedTurns', import_run.imported_turns,
            'rejectedTurns', import_run.rejected_turns
        )
    );
    return jsonb_build_object(
        'completed', true,
        'completedBatches', import_run.expected_batches,
        'expectedBatches', import_run.expected_batches,
        'importedTurns', import_run.imported_turns,
        'rejectedTurns', import_run.rejected_turns
    );
end;
$$;

create or replace function public.hope_memory_index_health()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select jsonb_build_object(
    'conversation_messages', coalesce((
        select indexes.indisvalid and indexes.indisready
        from pg_catalog.pg_index as indexes
        where indexes.indexrelid = to_regclass('public.hope_conversation_messages_search_idx')
    ), false),
    'memory_facts', coalesce((
        select indexes.indisvalid and indexes.indisready
        from pg_catalog.pg_index as indexes
        where indexes.indexrelid = to_regclass('public.hope_memory_facts_search_idx')
    ), false),
    'conversation_turns', coalesce((
        select indexes.indisvalid and indexes.indisready
        from pg_catalog.pg_index as indexes
        where indexes.indexrelid = to_regclass('public.hope_conversation_turns_search_document_idx')
    ), false)
    );
$$;

create or replace function public.hope_memory_dashboard_stats(owner_user_id text)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select jsonb_build_object(
        'conversationCount', (
            select count(distinct messages.conversation_id)
            from public.hope_conversation_messages as messages
            where messages.user_id = owner_user_id
              and messages.conversation_id is not null
        ),
        'turnCount', (
            select count(*)
            from public.hope_conversation_turns as turns
            where turns.user_id = owner_user_id
        ),
        'messageCount', (
            select count(*)
            from public.hope_conversation_messages as messages
            where messages.user_id = owner_user_id
        ),
        'currentFactCount', (
            select count(*)
            from public.hope_memory_facts as facts
            where facts.user_id = owner_user_id and facts.is_current
        ),
        'projectCount', (
            select count(*)
            from public.hope_memory_projects as projects
            where projects.user_id = owner_user_id
        ),
        'entityCount', (
            select count(*)
            from public.hope_memory_entities as entities
            where entities.user_id = owner_user_id
        ),
        'relationshipCount', (
            select count(*)
            from public.hope_memory_relationships as relationships
            where relationships.user_id = owner_user_id
        ),
        'failedExtractionJobs', (
            select count(*)
            from public.hope_memory_extraction_jobs as jobs
            where jobs.user_id = owner_user_id and jobs.status = 'failed'
        ),
        'failedConsolidationJobs', (
            select count(*)
            from public.hope_memory_consolidation_jobs as jobs
            where jobs.user_id = owner_user_id and jobs.status = 'failed'
        ),
        'lastIndexedAt', (
            select max(jobs.processed_at)
            from public.hope_memory_consolidation_jobs as jobs
            where jobs.user_id = owner_user_id and jobs.status = 'succeeded'
        ),
        'lastExportAt', (
            select max(exports.created_at)
            from public.hope_memory_export_runs as exports
            where exports.user_id = owner_user_id
        ),
        'verifiedExportCount', (
            select count(*)
            from public.hope_memory_export_runs as exports
            where exports.user_id = owner_user_id and exports.verified
        )
    );
$$;

revoke all on function public.claim_hope_memory_consolidation_jobs(text, integer)
    from public, anon, authenticated;
grant execute on function public.claim_hope_memory_consolidation_jobs(text, integer)
    to service_role;

revoke all on function public.search_hope_conversation_messages(text, text, integer, integer, text, timestamptz, timestamptz, text)
    from public, anon, authenticated;
grant execute on function public.search_hope_conversation_messages(text, text, integer, integer, text, timestamptz, timestamptz, text)
    to service_role;

revoke all on function public.save_hope_memory_fact(text, text, text, text, text, text, text, uuid, text, text, timestamptz, numeric, uuid, text, text)
    from public, anon, authenticated;
grant execute on function public.save_hope_memory_fact(text, text, text, text, text, text, text, uuid, text, text, timestamptz, numeric, uuid, text, text)
    to service_role;

revoke all on function public.search_hope_memory_facts(text, text, integer, integer, uuid, text, timestamptz, timestamptz, text)
    from public, anon, authenticated;
grant execute on function public.search_hope_memory_facts(text, text, integer, integer, uuid, text, timestamptz, timestamptz, text)
    to service_role;

revoke all on function public.correct_hope_memory_fact(text, uuid, text, text)
    from public, anon, authenticated;
grant execute on function public.correct_hope_memory_fact(text, uuid, text, text)
    to service_role;

revoke all on function public.delete_hope_memory_fact(text, uuid)
    from public, anon, authenticated;
grant execute on function public.delete_hope_memory_fact(text, uuid)
    to service_role;
revoke all on function public.record_hope_memory_import_batch(text, uuid, integer, integer, integer)
    from public, anon, authenticated;
grant execute on function public.record_hope_memory_import_batch(text, uuid, integer, integer, integer)
    to service_role;
revoke all on function public.complete_hope_memory_import(text, uuid)
    from public, anon, authenticated;
grant execute on function public.complete_hope_memory_import(text, uuid)
    to service_role;

revoke all on function public.hope_memory_index_health()
    from public, anon, authenticated;
grant execute on function public.hope_memory_index_health()
    to service_role;
revoke all on function public.hope_memory_dashboard_stats(text)
    from public, anon, authenticated;
grant execute on function public.hope_memory_dashboard_stats(text)
    to service_role;
