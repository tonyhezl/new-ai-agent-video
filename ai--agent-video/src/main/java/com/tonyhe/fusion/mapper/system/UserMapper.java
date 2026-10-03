package com.tonyhe.fusion.mapper.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tonyhe.fusion.entity.system.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Update("UPDATE sys_user SET credit_balance = credit_balance + #{amount}, " +
            "credit_version = credit_version + 1 " +
            "WHERE id = #{userId} AND credit_version = #{expectedVersion} AND deleted = 0")
    int updateCreditBalance(@Param("userId") Long userId, @Param("amount") int amount, @Param("expectedVersion") int expectedVersion);

    @Update("UPDATE sys_user SET credit_balance = credit_balance - #{amount}, " +
            "credit_version = credit_version + 1 " +
            "WHERE id = #{userId} AND credit_balance >= #{amount} AND credit_version = #{expectedVersion} AND deleted = 0")
    int deductCreditBalance(@Param("userId") Long userId, @Param("amount") int amount, @Param("expectedVersion") int expectedVersion);
}
