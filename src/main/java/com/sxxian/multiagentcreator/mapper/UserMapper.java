package com.sxxian.multiagentcreator.mapper;

import com.mybatisflex.core.BaseMapper;
import com.sxxian.multiagentcreator.model.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 原子扣减用户配额
     * 使用 quota > 0 条件确保并发安全，避免超扣
     *
     * @param userId 用户ID
     * @return 影响行数，1表示成功，0表示配额不足
     */
    @Update("UPDATE user SET quota = quota - 1 WHERE id = #{userId} AND quota > 0")
    int decrementQuota(@Param("userId") Long userId);

}
