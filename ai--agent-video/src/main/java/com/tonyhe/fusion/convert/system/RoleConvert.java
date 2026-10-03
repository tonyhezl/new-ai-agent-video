package com.tonyhe.fusion.convert.system;

import com.tonyhe.fusion.controller.system.vo.RoleRespVO;
import com.tonyhe.fusion.entity.system.Role;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 角色 Convert
 */
@Mapper
public interface RoleConvert {

    RoleConvert INSTANCE = Mappers.getMapper(RoleConvert.class);

    RoleRespVO convert(Role role);

    List<RoleRespVO> convertList(List<Role> roles);
}
