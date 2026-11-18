create table if not exists public.hope_core_behavior_instructions (
    user_id text not null,
    instruction_id uuid not null,
    instruction_text text not null check (char_length(instruction_text) between 1 and 2000),
    normalized_rule text not null check (char_length(normalized_rule) between 1 and 2000),
    category text not null check (category in (
        'communication_preferences',
        'behavioral_corrections',
        'whatsapp_communication_rules',
        'privacy_preferences',
        'task_execution_preferences',
        'permanent_restrictions',
        'user_specific_preferences'
    )),
    scope text not null check (scope in ('global', 'text', 'voice', 'whatsapp', 'background_agent')),
    priority smallint not null check (priority between 0 and 100),
    created_at bigint not null,
    updated_at bigint not null,
    active_status boolean not null default true,
    source_conversation text,
    confirmation_status text not null default 'confirmed_local',
    primary key (user_id, instruction_id)
);

create index if not exists hope_core_behavior_active_idx
    on public.hope_core_behavior_instructions (user_id, active_status, priority desc);

create table if not exists public.hope_core_behavior_history (
    user_id text not null,
    event_id uuid not null,
    instruction_id uuid not null,
    action text not null check (action in ('created', 'edited', 'disabled', 'reactivated', 'deleted', 'applied')),
    instruction_snapshot text not null check (char_length(instruction_snapshot) between 1 and 2000),
    surface text,
    occurred_at bigint not null,
    primary key (user_id, event_id)
);

create index if not exists hope_core_behavior_history_recent_idx
    on public.hope_core_behavior_history (user_id, occurred_at desc);

alter table public.hope_core_behavior_instructions enable row level security;
alter table public.hope_core_behavior_instructions force row level security;
alter table public.hope_core_behavior_history enable row level security;
alter table public.hope_core_behavior_history force row level security;

revoke all on table public.hope_core_behavior_instructions from public, anon, authenticated;
revoke all on table public.hope_core_behavior_history from public, anon, authenticated;
grant select, insert, update, delete on table public.hope_core_behavior_instructions to service_role;
grant select, insert, update, delete on table public.hope_core_behavior_history to service_role;
