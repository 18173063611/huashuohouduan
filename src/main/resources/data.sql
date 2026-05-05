insert into project(project_name, description, status)
select 'AI 数字人口播 MVP 演示项目', '用于演示项目管理、任务中心和资产中心基础能力', 'DRAFT'
where not exists (
    select 1 from project where project_name = 'AI 数字人口播 MVP 演示项目' and deleted = 0
);

insert into task(project_id, task_type, status, input_json, output_json, retry_count, trace_id)
select p.project_id, 'SCRIPT_REWRITE', 'SUCCESS',
       '{"source":"demo script","goal":"生成可联调用例"}',
       '{"summary":"已生成演示文案版本"}',
       0,
       'demo-trace-001'
from project p
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and p.deleted = 0
  and not exists (select 1 from task t where t.project_id = p.project_id and t.task_type = 'SCRIPT_REWRITE' and t.deleted = 0);

insert into asset(project_id, task_id, asset_type, file_name, file_url, thumbnail_url, mime_type, file_size, source_type, metadata_json)
select p.project_id, t.task_id, 'TEXT', 'demo-script.txt', '/uploads/demo-script.txt', null, 'text/plain', 128, 'DEMO',
       '{"description":"资产中心演示文案"}'
from project p
left join task t on t.project_id = p.project_id and t.task_type = 'SCRIPT_REWRITE' and t.deleted = 0
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and p.deleted = 0
  and not exists (select 1 from asset a where a.project_id = p.project_id and a.file_name = 'demo-script.txt' and a.deleted = 0);

insert into script_version(project_id, version_no, content, source_type)
select p.project_id, 1, '大家好，今天演示 AI 数字人视频制作的基础工作台。', 'DEMO'
from project p
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and p.deleted = 0
  and not exists (select 1 from script_version s where s.project_id = p.project_id and s.version_no = 1 and s.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_shuangkuaisisi_moon_bigtts', '清爽女声', 'FEMALE', '知识口播', null, 1
where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_shuangkuaisisi_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_liufei_uranus_bigtts', '沉稳男声', 'MALE', '品牌讲解', null, 1
where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_liufei_uranus_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_wanwanxiaohe_moon_bigtts', '活力女声', 'FEMALE', '带货促销', null, 1
where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_wanwanxiaohe_moon_bigtts' and v.deleted = 0);

-- 兼容历史数据：若已存在旧的沉稳男声 speaker，统一迁移为可用的男声 speaker，避免 resourceId 与 speaker 不匹配。
update voice_profile
set provider_voice_id = 'zh_male_liufei_uranus_bigtts'
where deleted = 0
  and provider = 'DOUBAO'
  and (
    provider_voice_id in ('zh_male_chunhou_moon_bigtts', 'zh_male_bvlazysheep', 'zh_male_chunhou')
    or voice_name = '沉稳男声'
  );
