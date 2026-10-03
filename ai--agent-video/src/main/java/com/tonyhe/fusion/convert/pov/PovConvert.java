package com.tonyhe.fusion.convert.pov;

import com.tonyhe.fusion.controller.pov.vo.*;
import com.tonyhe.fusion.entity.pov.PovShot;
import com.tonyhe.fusion.entity.pov.PovStory;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
public interface PovConvert {

    PovConvert INSTANCE = Mappers.getMapper(PovConvert.class);

    PovStoryRespVO convert(PovStory bean);

    List<PovStoryRespVO> convertList(List<PovStory> list);

    PovShotRespVO convert(PovShot bean);

    List<PovShotRespVO> convertShotList(List<PovShot> list);
}
