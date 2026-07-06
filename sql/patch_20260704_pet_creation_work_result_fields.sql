-- 宠物创作中心作品结果回写字段补丁。
-- 已执行过 patch_20260704_pet_creation_backend.sql 的环境继续执行本脚本。
-- MySQL 8.0.46 在部分环境不接受 "add column if not exists" 组合写法，
-- 这里使用 information_schema + dynamic SQL 保持幂等，只补 pet_video_work 指定字段。

set @schema_name = database();

set @sql = if(
    (select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'pet_video_work' and column_name = 'result_asset_id') = 0,
    'alter table pet_video_work add column result_asset_id bigint comment ''生成结果资产ID，关联资产中心或 task.result_asset_id'' after cover_url',
    'select ''result_asset_id exists'' as patch_skip'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if(
    (select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'pet_video_work' and column_name = 'completed_at') = 0,
    'alter table pet_video_work add column completed_at datetime comment ''作品完成时间'' after result_asset_id',
    'select ''completed_at exists'' as patch_skip'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if(
    (select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'pet_video_work' and column_name = 'error_code') = 0,
    'alter table pet_video_work add column error_code varchar(80) comment ''失败错误码'' after completed_at',
    'select ''error_code exists'' as patch_skip'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if(
    (select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'pet_video_work' and column_name = 'error_message') = 0,
    'alter table pet_video_work add column error_message varchar(1000) comment ''失败原因'' after error_code',
    'select ''error_message exists'' as patch_skip'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if(
    (select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'pet_video_work' and column_name = 'retryable') = 0,
    'alter table pet_video_work add column retryable tinyint(1) comment ''失败是否可重试：1=可重试，0=不可重试'' after error_message',
    'select ''retryable exists'' as patch_skip'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if(
    (select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'pet_video_work' and column_name = 'provider_metadata_json') = 0,
    'alter table pet_video_work add column provider_metadata_json longtext comment ''第三方任务/结果诊断元数据 JSON'' after retryable',
    'select ''provider_metadata_json exists'' as patch_skip'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
