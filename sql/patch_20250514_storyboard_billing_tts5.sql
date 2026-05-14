-- 存量库补丁：分镜解析 task_type 计费种子 + TTS 预扣改为 5 积分（与 schema.sql 新装一致）。
-- 执行前请备份；可重复执行（insert 使用 where not exists；update 为幂等覆盖目标值）。

-- 1) VIDEO_SCRIPT_ANALYZE / VIDEO_SCRIPT_URL_ANALYZE（与 STORYBOARD_GENERATE 两步口径一致）
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_SCRIPT_ANALYZE', '分镜解析', '分镜脚本生成', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 10, '上传视频后走视觉模型解析'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_SCRIPT_ANALYZE' and c.step_name = '分镜脚本生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_SCRIPT_ANALYZE', '分镜解析', '镜头拆分', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 20, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_SCRIPT_ANALYZE' and c.step_name = '镜头拆分' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_SCRIPT_URL_ANALYZE', '分镜解析', '分镜脚本生成', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 10, '分享链接解析后再走视觉模型'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_SCRIPT_URL_ANALYZE' and c.step_name = '分镜脚本生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_SCRIPT_URL_ANALYZE', '分镜解析', '镜头拆分', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 20, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_SCRIPT_URL_ANALYZE' and c.step_name = '镜头拆分' and c.deleted = 0);

-- 2) TTS：主步骤 5 分；轮询步骤不参与预扣
update ai_billing_step_config
set credit_cost = 5
where task_type = 'TTS_GENERATE' and step_name = '文本转语音' and deleted = 0;

update ai_billing_step_config
set credit_cost = 0, enabled = 0
where task_type = 'TTS_GENERATE' and step_name = '音频查询轮询' and deleted = 0;
