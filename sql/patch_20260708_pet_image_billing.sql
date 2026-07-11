-- Pet Creation Center image billing seed.
-- Safe to run repeatedly.

insert into ai_model_price(provider, model_code, model_name, task_type, usage_unit, unit_credit_price,
                           estimate_output_ratio, estimate_buffer_ratio, enabled)
select 'VOLCENGINE', 'doubao-seedream-5-0-260128', 'Seedream Pet Image',
       'PET_IMAGE_GENERATE', 'IMAGE', 5.000000, 1.0000, 1.0000, 1
where not exists (
    select 1 from ai_model_price p
    where p.provider = 'VOLCENGINE'
      and p.model_code = 'doubao-seedream-5-0-260128'
      and p.task_type = 'PET_IMAGE_GENERATE'
      and p.deleted = 0
);

insert into ai_model_price(provider, model_code, model_name, task_type, usage_unit, unit_credit_price,
                           estimate_output_ratio, estimate_buffer_ratio, enabled)
select 'VOLCENGINE', 'doubao-seedream-5-0-260128', 'Seedream Pet Background',
       'PET_BACKGROUND_GENERATE', 'IMAGE', 5.000000, 1.0000, 1.0000, 1
where not exists (
    select 1 from ai_model_price p
    where p.provider = 'VOLCENGINE'
      and p.model_code = 'doubao-seedream-5-0-260128'
      and p.task_type = 'PET_BACKGROUND_GENERATE'
      and p.deleted = 0
);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code,
                                   usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'PET_IMAGE_GENERATE', 'Pet Creation', 'Pet image generation',
       'VOLCENGINE', 'doubao-seedream-5-0-260128', 'IMAGE', '1 image', 'Seedream image', 5, 1, 10,
       'Pet Creation Center static sticker/image generation'
where not exists (
    select 1 from ai_billing_step_config c
    where c.task_type = 'PET_IMAGE_GENERATE'
      and c.step_name = 'Pet image generation'
      and c.deleted = 0
);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code,
                                   usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'PET_BACKGROUND_GENERATE', 'Pet Creation', 'Pet background generation',
       'VOLCENGINE', 'doubao-seedream-5-0-260128', 'IMAGE', '1 image', 'Seedream image', 5, 1, 10,
       'Pet Creation Center background image generation'
where not exists (
    select 1 from ai_billing_step_config c
    where c.task_type = 'PET_BACKGROUND_GENERATE'
      and c.step_name = 'Pet background generation'
      and c.deleted = 0
);
