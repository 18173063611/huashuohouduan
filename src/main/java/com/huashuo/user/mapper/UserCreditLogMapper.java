package com.huashuo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huashuo.user.entity.UserCreditLogEntity;

/**
 * 用户积分流水数据访问层：流水只追加，不承载余额计算规则。
 */
public interface UserCreditLogMapper extends BaseMapper<UserCreditLogEntity> {
}
