package com.tonyhe.fusion.controller.team.vo;

import com.tonyhe.fusion.common.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "团队分页查询请求")
@Data
@EqualsAndHashCode(callSuper = true)
public class TeamPageReqVO extends PageParam {
    private String name;
    private Integer status;
}
