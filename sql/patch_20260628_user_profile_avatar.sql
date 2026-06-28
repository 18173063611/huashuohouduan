-- 用户个人中心头像字段增量补丁。
-- 线上已有数据库执行前仍需先备份；本补丁只在字段不存在时新增 avatar_url。

delimiter $$

drop procedure if exists hs_add_column_if_missing $$
create procedure hs_add_column_if_missing(
    in p_table_name varchar(128),
    in p_column_name varchar(128),
    in p_column_ddl text
)
begin
    if exists (
        select 1
        from information_schema.tables
        where table_schema = database()
          and table_name = p_table_name
    ) and not exists (
        select 1
        from information_schema.columns
        where table_schema = database()
          and table_name = p_table_name
          and column_name = p_column_name
    ) then
        set @hs_sql = concat('alter table `', p_table_name, '` add column ', p_column_ddl);
        prepare hs_stmt from @hs_sql;
        execute hs_stmt;
        deallocate prepare hs_stmt;
    end if;
end $$

delimiter ;

call hs_add_column_if_missing(
    'user_account',
    'avatar_url',
    '`avatar_url` varchar(500) comment ''用户头像URL'' after `display_name`'
);

drop procedure if exists hs_add_column_if_missing;
