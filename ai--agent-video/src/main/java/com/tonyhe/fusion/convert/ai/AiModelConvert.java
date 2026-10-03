package com.tonyhe.fusion.convert.ai;

import com.tonyhe.fusion.controller.ai.vo.AiModelRespVO;
import com.tonyhe.fusion.entity.ai.AiModel;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * AI模型 Convert
 */
@Mapper
public interface AiModelConvert {

    AiModelConvert INSTANCE = Mappers.getMapper(AiModelConvert.class);

    AiModelRespVO convert(AiModel model);

    List<AiModelRespVO> convertList(List<AiModel> models);
}
