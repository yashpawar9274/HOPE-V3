create or replace function public.publish_hope_agent_plan(
    owner_user_id text,
    target_task_id uuid,
    expected_execution_attempt integer,
    new_plan jsonb,
    new_tool_calls jsonb
)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    target_state text;
    proposal jsonb;
    proposal_count integer;
    call_id uuid;
    tool_name text;
    parameters jsonb;
    permission_scope text;
    risk_category text;
begin
    if jsonb_typeof(new_plan) is distinct from 'array'
       or jsonb_typeof(new_tool_calls) is distinct from 'array' then
        raise exception using errcode = '22023', message = 'agent_plan_invalid';
    end if;
    if jsonb_array_length(new_plan) > 6
       or jsonb_array_length(new_tool_calls) > 5 then
        raise exception using errcode = '22023', message = 'agent_plan_invalid';
    end if;

    perform 1
    from public.hope_agent_consents as consents
    where consents.user_id = owner_user_id
      and consents.enabled
    for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'agent_task_not_publishable';
    end if;

    select tasks.state into target_state
    from public.hope_agent_tasks as tasks
    where tasks.user_id = owner_user_id
      and tasks.task_id = target_task_id
      and tasks.state = 'planning'
      and not tasks.cancellation_requested
      and tasks.execution_attempts = expected_execution_attempt
      and tasks.lease_expires_at > now()
    for update;

    if target_state is null then
        raise exception using errcode = 'P0001', message = 'agent_task_not_publishable';
    end if;

    proposal_count := jsonb_array_length(new_tool_calls);
    for proposal in select value from jsonb_array_elements(new_tool_calls)
    loop
        if jsonb_typeof(proposal) is distinct from 'object'
           or jsonb_object_length(proposal) <> 5 then
            raise exception using errcode = '22023', message = 'agent_tool_proposal_invalid';
        end if;
        call_id := (proposal->>'tool_call_id')::uuid;
        tool_name := proposal->>'tool_name';
        parameters := proposal->'parameters';
        permission_scope := proposal->>'permission_scope';
        risk_category := proposal->>'risk_category';

        if jsonb_typeof(parameters) is distinct from 'object' then
            raise exception using errcode = '22023', message = 'agent_tool_parameters_invalid';
        end if;
        if tool_name = 'get_device_context' then
            if parameters <> '{}'::jsonb
               or permission_scope <> 'device.context.read'
               or risk_category <> 'low' then
                raise exception using errcode = '22023', message = 'agent_tool_proposal_invalid';
            end if;
        elsif tool_name = 'set_screen_brightness' then
            if jsonb_object_length(parameters) <> 1
               or jsonb_typeof(parameters->'percent') is distinct from 'number'
               or permission_scope <> 'system_settings.write'
               or risk_category <> 'moderate' then
                raise exception using errcode = '22023', message = 'agent_tool_proposal_invalid';
            end if;
            if parameters->>'percent' !~ '^(100|[1-9][0-9]?|[5-9])$' then
                raise exception using errcode = '22023', message = 'agent_tool_proposal_invalid';
            end if;
            if (parameters->>'percent')::integer not between 5 and 100 then
                raise exception using errcode = '22023', message = 'agent_tool_proposal_invalid';
            end if;
        else
            raise exception using errcode = '22023', message = 'agent_tool_not_supported';
        end if;

        insert into public.hope_agent_tool_calls (
            user_id, task_id, tool_call_id, tool_name, parameters,
            permission_scope, risk_category, status, expires_at
        ) values (
            owner_user_id, target_task_id, call_id, tool_name, parameters,
            permission_scope, risk_category, 'requested', now() + interval '10 minutes'
        );
    end loop;

    update public.hope_agent_tasks as tasks
    set plan = new_plan,
        state = case when proposal_count > 0 then 'awaiting_approval' else 'completed' end,
        approval_status = case when proposal_count > 0 then 'pending' else 'not_required' end,
        lease_expires_at = null,
        completed_at = case when proposal_count = 0 then now() else null end,
        updated_at = now()
    where tasks.user_id = owner_user_id
      and tasks.task_id = target_task_id;

    perform public.append_hope_agent_event(
        owner_user_id,
        target_task_id,
        'task.plan_ready',
        jsonb_build_object('actionCount', proposal_count)
    );

    for proposal in select value from jsonb_array_elements(new_tool_calls)
    loop
        perform public.append_hope_agent_event(
            owner_user_id,
            target_task_id,
            'tool.requested',
            jsonb_build_object(
                'toolCallId', proposal->>'tool_call_id',
                'toolName', proposal->>'tool_name'
            )
        );
    end loop;

    return case when proposal_count > 0 then 'awaiting_approval' else 'completed' end;
end;
$$;

revoke all on function public.publish_hope_agent_plan(text, uuid, integer, jsonb, jsonb)
    from public, anon, authenticated;
grant execute on function public.publish_hope_agent_plan(text, uuid, integer, jsonb, jsonb)
    to service_role;

create or replace function public.finish_hope_agent_task(
    owner_user_id text,
    target_task_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    target_state text;
    action_count integer;
    all_verified boolean;
    final_state text;
    final_verification text;
begin
    select tasks.state into target_state
    from public.hope_agent_tasks as tasks
    where tasks.user_id = owner_user_id
      and tasks.task_id = target_task_id
      and tasks.state in ('awaiting_approval', 'waiting_for_device')
    for update;

    if target_state is null then
        raise exception using errcode = 'P0001', message = 'agent_task_not_finishable';
    end if;

    select count(*)::integer,
           coalesce(bool_and(
               calls.status = 'executed'
               and calls.result->>'verified' = 'true'
           ), false)
      into action_count, all_verified
    from public.hope_agent_tool_calls as calls
    where calls.user_id = owner_user_id
      and calls.task_id = target_task_id;

    if action_count = 0 or exists (
        select 1
        from public.hope_agent_tool_calls as calls
        where calls.user_id = owner_user_id
          and calls.task_id = target_task_id
          and calls.status in ('requested', 'user_confirmation_required')
    ) then
        raise exception using errcode = 'P0001', message = 'agent_tool_results_incomplete';
    end if;

    final_state := case when all_verified then 'completed' else 'failed' end;
    final_verification := case when all_verified then 'passed' else 'failed' end;
    update public.hope_agent_tasks as tasks
    set state = final_state,
        approval_status = case when all_verified then 'approved' else 'denied' end,
        verification_status = final_verification,
        failure_code = case when all_verified then null else 'device_action_not_verified' end,
        lease_expires_at = null,
        completed_at = now(),
        updated_at = now()
    where tasks.user_id = owner_user_id
      and tasks.task_id = target_task_id;

    perform public.append_hope_agent_event(
        owner_user_id,
        target_task_id,
        case when all_verified then 'task.completed' else 'task.failed' end,
        jsonb_build_object('verificationStatus', final_verification)
    );

    return jsonb_build_object(
        'task_id', target_task_id,
        'state', final_state,
        'approval_status', case when all_verified then 'approved' else 'denied' end,
        'verification_status', final_verification
    );
end;
$$;

revoke all on function public.finish_hope_agent_task(text, uuid)
    from public, anon, authenticated;
grant execute on function public.finish_hope_agent_task(text, uuid)
    to service_role;

create or replace function public.revoke_hope_agent_consent(owner_user_id text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    active_task record;
begin
    insert into public.hope_agent_consents (user_id, enabled, updated_at)
    values (owner_user_id, false, now())
    on conflict (user_id) do update
      set enabled = false,
          updated_at = now();

    for active_task in
        select tasks.task_id
        from public.hope_agent_tasks as tasks
        where tasks.user_id = owner_user_id
          and tasks.state not in ('completed', 'failed', 'cancelled')
        for update
    loop
        update public.hope_agent_tasks as tasks
        set state = 'cancelled',
            cancellation_requested = true,
            lease_expires_at = null,
            completed_at = now(),
            updated_at = now()
        where tasks.user_id = owner_user_id
          and tasks.task_id = active_task.task_id;

        update public.hope_agent_tool_calls as calls
        set status = 'cancelled',
            updated_at = now()
        where calls.user_id = owner_user_id
          and calls.task_id = active_task.task_id
          and calls.status in ('requested', 'user_confirmation_required');

        perform public.append_hope_agent_event(
            owner_user_id,
            active_task.task_id,
            'task.cancelled',
            jsonb_build_object('reason', 'cloud_agent_disabled')
        );
    end loop;
end;
$$;

revoke all on function public.revoke_hope_agent_consent(text)
    from public, anon, authenticated;
grant execute on function public.revoke_hope_agent_consent(text)
    to service_role;
