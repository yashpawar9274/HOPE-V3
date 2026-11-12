drop function if exists public.search_hope_conversation_turns(text, integer);

create function public.search_hope_conversation_turns(
    search_query text,
    owner_user_id text,
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
    where turns.user_id = owner_user_id
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

revoke all on function public.search_hope_conversation_turns(text, text, integer)
    from public, anon, authenticated;
grant execute on function public.search_hope_conversation_turns(text, text, integer)
    to service_role;
