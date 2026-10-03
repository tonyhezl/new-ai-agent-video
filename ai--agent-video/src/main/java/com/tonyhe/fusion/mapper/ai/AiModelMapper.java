package com.tonyhe.fusion.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tonyhe.fusion.entity.ai.AiModel;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {

		@Update("""
						UPDATE afv_ai_model
						SET deleted = 1,
								deleted_id = id,
								update_time = NOW()
						WHERE id = #{id}
							AND deleted = 0
						""")
		int softDeleteById(@Param("id") Long id);

		@org.apache.ibatis.annotations.Delete("""
						DELETE FROM afv_ai_model WHERE id = #{id}
						""")
		int physicalDeleteById(@Param("id") Long id);

	@org.apache.ibatis.annotations.Delete("""
					DELETE FROM afv_ai_model WHERE api_config_id = #{apiConfigId}
					""")
	int physicalDeleteByApiConfigId(@Param("apiConfigId") Long apiConfigId);
}
