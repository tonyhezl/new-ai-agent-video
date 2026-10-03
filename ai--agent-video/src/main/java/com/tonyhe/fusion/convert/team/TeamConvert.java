package com.tonyhe.fusion.convert.team;

import com.tonyhe.fusion.controller.team.vo.TeamMemberRespVO;
import com.tonyhe.fusion.controller.team.vo.TeamRespVO;
import com.tonyhe.fusion.entity.team.Team;
import com.tonyhe.fusion.entity.team.TeamMember;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 团队 Convert
 */
@Mapper
public interface TeamConvert {

    TeamConvert INSTANCE = Mappers.getMapper(TeamConvert.class);

    @Mapping(target = "memberCount", ignore = true)
    TeamRespVO convert(Team team);

    TeamMemberRespVO convert(TeamMember member);

    List<TeamMemberRespVO> convertMemberList(List<TeamMember> members);
}
