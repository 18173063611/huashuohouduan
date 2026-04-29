package com.huashuo.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huashuo.asset.entity.AssetEntity;

/**
 * 资产数据访问层：只负责 asset 表的基础 CRUD，业务组装放在 AssetServiceImpl。
 */
public interface AssetMapper extends BaseMapper<AssetEntity> {
}
