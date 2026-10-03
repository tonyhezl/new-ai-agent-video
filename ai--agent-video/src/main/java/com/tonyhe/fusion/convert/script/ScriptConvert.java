package com.tonyhe.fusion.convert.script;

import com.tonyhe.fusion.controller.script.vo.EpisodeCreateReqVO;
import com.tonyhe.fusion.controller.script.vo.EpisodeUpdateReqVO;
import com.tonyhe.fusion.controller.script.vo.SceneCreateReqVO;
import com.tonyhe.fusion.controller.script.vo.SceneUpdateReqVO;
import com.tonyhe.fusion.controller.script.vo.ScriptCreateReqVO;
import com.tonyhe.fusion.controller.script.vo.ScriptUpdateReqVO;
import com.tonyhe.fusion.entity.script.ScriptSceneItem;
import com.tonyhe.fusion.entity.script.Script;
import com.tonyhe.fusion.entity.script.ScriptEpisode;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 剧本 Convert
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, builder = @Builder(disableBuilder = true))
public interface ScriptConvert {

    ScriptConvert INSTANCE = Mappers.getMapper(ScriptConvert.class);

    // ========== 剧本 ==========

    Script convert(ScriptCreateReqVO reqVO);

    Script convert(ScriptUpdateReqVO reqVO);

    // ========== 分集 ==========

    ScriptEpisode convert(EpisodeCreateReqVO reqVO);

    ScriptEpisode convert(EpisodeUpdateReqVO reqVO);

    // ========== 场次 ==========

    ScriptSceneItem convert(SceneCreateReqVO reqVO);

    ScriptSceneItem convert(SceneUpdateReqVO reqVO);
}
