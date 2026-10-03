package com.tonyhe.fusion.convert.system;

import com.tonyhe.fusion.controller.system.vo.UserRespVO;
import com.tonyhe.fusion.entity.system.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 用户 Convert
 */
@Mapper
public interface UserConvert {

    UserConvert INSTANCE = Mappers.getMapper(UserConvert.class);

    @Mapping(target = "roles", ignore = true)
    UserRespVO convert(User user);

    List<UserRespVO> convertList(List<User> users);
}
