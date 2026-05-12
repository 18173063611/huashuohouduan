package com.huashuo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huashuo.user.entity.UserCreditAccountEntity;

/**
 * 用户积分账户数据访问层：只负责账户快照表读写，扣费规则放在 Service 层。
 */
public interface UserCreditAccountMapper extends BaseMapper<UserCreditAccountEntity> {
}
