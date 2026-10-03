package com.tonyhe.fusion.convert.ai;

import com.tonyhe.fusion.controller.ai.vo.ApiConfigRespVO;
import com.tonyhe.fusion.entity.ai.ApiConfig;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * API配置 Convert
 */
@Mapper
public interface ApiConfigConvert {

    ApiConfigConvert INSTANCE = Mappers.getMapper(ApiConfigConvert.class);

    ApiConfigRespVO convert(ApiConfig config);

    List<ApiConfigRespVO> convertList(List<ApiConfig> configs);
}
