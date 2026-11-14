alter table public.hope_conversation_turns
    add column if not exists source_created_at timestamptz,
    add column if not exists source_timestamp_origin text,
    add column if not exists source_conversation_id text;

update public.hope_conversation_turns
set source_created_at = coalesce(source_created_at, created_at),
    source_timestamp_origin = coalesce(source_timestamp_origin, 'server_ingested');

alter table public.hope_conversation_turns
    alter column source_created_at set default now(),
    alter column source_created_at set not null,
    alter column source_timestamp_origin set default 'server_ingested',
    alter column source_timestamp_origin set not null;

alter table public.hope_conversation_turns
    drop constraint if exists hope_conversation_turns_timestamp_origin_check,
    add constraint hope_conversation_turns_timestamp_origin_check
        check (source_timestamp_origin in ('client_recorded', 'server_ingested')),
    drop constraint if exists hope_conversation_turns_source_conversation_id_check,
    add constraint hope_conversation_turns_source_conversation_id_check
        check (
            source_conversation_id is null
            or length(source_conversation_id) between 1 and 160
        ),
    add column if not exists search_document tsvector
        generated always as (
            to_tsvector(
                'simple',
                coalesce(user_text, '') || ' ' || coalesce(assistant_text, '')
            )
        ) stored;

create index if not exists hope_conversation_turns_search_document_idx
    on public.hope_conversation_turns using gin (search_document);

create index if not exists hope_conversation_turns_user_source_created_idx
    on public.hope_conversation_turns (user_id, source_created_at desc);

drop function if exists public.search_hope_conversation_turns(text, text, integer);

create function public.search_hope_conversation_turns(
    search_query text,
    owner_user_id text,
    max_results integer default 5
)
returns table (
    turn_id uuid,
    user_text text,
    assistant_text text,
    source_created_at timestamptz,
    source_timestamp_origin text,
    source_conversation_id text
)
language sql
stable
security invoker
set search_path = ''
as $$
    select
        turns.turn_id,
        turns.user_text,
        turns.assistant_text,
        turns.source_created_at,
        turns.source_timestamp_origin,
        turns.source_conversation_id
    from public.hope_conversation_turns as turns
    where turns.user_id = owner_user_id
      and search_query is not null
      and length(search_query) between 1 and 2000
      and turns.search_document @@ websearch_to_tsquery('simple', search_query)
    order by ts_rank(
        turns.search_document,
        websearch_to_tsquery('simple', search_query)
    ) desc, turns.source_created_at desc
    limit least(greatest(coalesce(max_results, 5), 1), 10);
$$;

revoke all on function public.search_hope_conversation_turns(text, text, integer)
    from public, anon, authenticated;
grant execute on function public.search_hope_conversation_turns(text, text, integer)
    to service_role;
