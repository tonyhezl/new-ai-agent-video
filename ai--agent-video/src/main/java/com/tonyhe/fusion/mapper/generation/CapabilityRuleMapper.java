package com.tonyhe.fusion.mapper.generation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tonyhe.fusion.entity.generation.CapabilityRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CapabilityRuleMapper extends BaseMapper<CapabilityRule> {

    @Update("""
            UPDATE afv_capability_rule
            SET deleted = 1,
                deleted_id = id,
                update_time = NOW()
            WHERE id = #{id}
                AND deleted = 0
            """)
    int softDeleteById(@Param("id") Long id);
}
