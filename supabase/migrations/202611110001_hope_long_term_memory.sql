create table if not exists public.hope_conversation_turns (
    user_id text not null,
    turn_id uuid not null,
    user_text text not null check (length(user_text) between 1 and 20000),
    assistant_text text not null check (length(assistant_text) <= 20000),
    source text not null check (source in ('chat', 'live_voice', 'manual')),
    created_at timestamptz not null default now(),
    primary key (user_id, turn_id)
);

alter table public.hope_conversation_turns enable row level security;
alter table public.hope_conversation_turns force row level security;

revoke all on table public.hope_conversation_turns from anon;
grant select, insert, update, delete on table public.hope_conversation_turns to authenticated;

drop policy if exists hope_conversation_turns_select_owner on public.hope_conversation_turns;
create policy hope_conversation_turns_select_owner
    on public.hope_conversation_turns for select to authenticated
    using ((select auth.jwt() ->> 'sub') = user_id);

drop policy if exists hope_conversation_turns_insert_owner on public.hope_conversation_turns;
create policy hope_conversation_turns_insert_owner
    on public.hope_conversation_turns for insert to authenticated
    with check ((select auth.jwt() ->> 'sub') = user_id);

drop policy if exists hope_conversation_turns_update_owner on public.hope_conversation_turns;
create policy hope_conversation_turns_update_owner
    on public.hope_conversation_turns for update to authenticated
    using ((select auth.jwt() ->> 'sub') = user_id)
    with check ((select auth.jwt() ->> 'sub') = user_id);

drop policy if exists hope_conversation_turns_delete_owner on public.hope_conversation_turns;
create policy hope_conversation_turns_delete_owner
    on public.hope_conversation_turns for delete to authenticated
    using ((select auth.jwt() ->> 'sub') = user_id);

create or replace function public.search_hope_conversation_turns(
    search_query text,
    max_results integer default 5
)
returns table (
    turn_id uuid,
    user_text text,
    assistant_text text,
    created_at timestamptz
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
        turns.created_at
    from public.hope_conversation_turns as turns
    where turns.user_id = (select auth.jwt() ->> 'sub')
      and search_query is not null
      and length(search_query) between 1 and 2000
      and to_tsvector(
          'simple',
          coalesce(turns.user_text, '') || ' ' || coalesce(turns.assistant_text, '')
      ) @@ websearch_to_tsquery('simple', search_query)
    order by ts_rank(
        to_tsvector(
            'simple',
            coalesce(turns.user_text, '') || ' ' || coalesce(turns.assistant_text, '')
        ),
        websearch_to_tsquery('simple', search_query)
    ) desc, turns.created_at desc
    limit least(greatest(coalesce(max_results, 5), 1), 10);
$$;

revoke all on function public.search_hope_conversation_turns(text, integer) from public, anon;
grant execute on function public.search_hope_conversation_turns(text, integer) to authenticated;

create index if not exists hope_conversation_turns_user_created_idx
    on public.hope_conversation_turns (user_id, created_at desc);
