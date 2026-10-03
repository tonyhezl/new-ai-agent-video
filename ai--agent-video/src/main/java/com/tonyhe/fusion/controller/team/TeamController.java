package com.tonyhe.fusion.controller.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.common.PageResult;
import com.tonyhe.fusion.controller.team.vo.*;
import com.tonyhe.fusion.convert.team.TeamConvert;
import com.tonyhe.fusion.entity.team.Team;
import com.tonyhe.fusion.entity.team.TeamMember;
import com.tonyhe.fusion.mapper.team.TeamMemberMapper;
import com.tonyhe.fusion.service.team.TeamService;
import com.tonyhe.fusion.security.ResourceAccessService;
import com.tonyhe.fusion.service.system.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.tonyhe.fusion.common.CommonResult.success;
import static com.tonyhe.fusion.security.SecurityUtils.getCurrentUserId;

@Tag(name = "团队管理")
@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final TeamMemberMapper teamMemberMapper;
    private final ResourceAccessService resourceAccessService;
    private final UserService userService;

    @PostMapping("/create")
    @Operation(summary = "创建团队")
    @PreAuthorize("@permissionService.has('team:create')")
    public CommonResult<Long> createTeam(@Valid @RequestBody TeamCreateReqVO reqVO) {
        // ownerUserId 由后端从 token 获取
        Team team = teamService.createTeam(reqVO.getName(), reqVO.getDescription(), getCurrentUserId());
        return success(team.getId());
    }

    @GetMapping("/get")
    @Operation(summary = "获取团队详情")
    @Parameter(name = "id", description = "团队ID", required = true)
    public CommonResult<TeamRespVO> getTeam(@RequestParam("id") Long id) {
        resourceAccessService.checkTeamMember(id, getCurrentUserId());
        Team team = teamService.getById(id);
        return success(team == null ? null : enrichTeamVO(team));
    }

    @GetMapping("/page")
    @Operation(summary = "团队分页列表")
    @PreAuthorize("@permissionService.has('team:view')")
    public CommonResult<PageResult<TeamRespVO>> getTeamPage(@Valid TeamPageReqVO reqVO) {
        Long userId = getCurrentUserId();
        PageResult<Team> page = userService.isAdmin(userId)
                ? teamService.getPage(reqVO.getName(), reqVO.getStatus(), reqVO.getPageNo(), reqVO.getPageSize())
                : teamService.getPageForUser(userId, reqVO.getName(), reqVO.getStatus(),
                        reqVO.getPageNo(), reqVO.getPageSize());
        return success(page
                .map(this::enrichTeamVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新团队")
    public CommonResult<Boolean> updateTeam(@Valid @RequestBody TeamUpdateReqVO reqVO) {
        resourceAccessService.checkTeamManagement(reqVO.getId(), getCurrentUserId());
        teamService.updateTeam(reqVO.getId(), reqVO.getName(), reqVO.getDescription(), reqVO.getLogo(), reqVO.getStatus());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除团队")
    @PreAuthorize("@permissionService.has('team:view')")
    public CommonResult<Boolean> deleteTeam(@RequestParam("id") Long id) {
        resourceAccessService.checkTeamManagement(id, getCurrentUserId());
        teamService.deleteTeam(id);
        return success(true);
    }

    // ==================== 成员管理 ====================

    @PostMapping("/member/add")
    @Operation(summary = "添加团队成员")
    public CommonResult<Long> addMember(@Valid @RequestBody TeamMemberAddReqVO reqVO) {
        resourceAccessService.checkTeamManagement(reqVO.getTeamId(), getCurrentUserId());
        return success(teamService.addMember(reqVO.getTeamId(), reqVO.getUserId(), reqVO.getRole()));
    }

    @PostMapping("/member/remove")
    @Operation(summary = "移除团队成员")
    public CommonResult<Boolean> removeMember(@RequestParam("teamId") Long teamId, @RequestParam("userId") Long userId) {
        resourceAccessService.checkTeamManagement(teamId, getCurrentUserId());
        teamService.removeMember(teamId, userId);
        return success(true);
    }

    @PostMapping("/member/change-role")
    @Operation(summary = "变更成员角色")
    public CommonResult<Boolean> changeMemberRole(@RequestParam("teamId") Long teamId,
                                                   @RequestParam("userId") Long userId,
                                                   @RequestParam("role") Integer role) {
        resourceAccessService.checkTeamManagement(teamId, getCurrentUserId());
        teamService.changeMemberRole(teamId, userId, role);
        return success(true);
    }

    @GetMapping("/member/list")
    @Operation(summary = "获取团队成员列表")
    public CommonResult<List<TeamMemberRespVO>> getMemberList(@RequestParam("teamId") Long teamId) {
        resourceAccessService.checkTeamMember(teamId, getCurrentUserId());
        return success(TeamConvert.INSTANCE.convertMemberList(teamService.getMemberList(teamId)));
    }

    @PutMapping("/current")
    @Operation(summary = "切换当前团队")
    public CommonResult<Boolean> switchCurrentTeam(@RequestParam("teamId") Long teamId, HttpServletRequest request) {
        teamService.switchCurrentTeam(getCurrentUserId(), teamId, getTokenFromRequest(request));
        return success(true);
    }

    /**
     * MapStruct 自动映射基础字段 + 手动补充 memberCount
     */
    private TeamRespVO enrichTeamVO(Team team) {
        TeamRespVO vo = TeamConvert.INSTANCE.convert(team);
        vo.setMemberCount(teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId())));
        return vo;
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken)) {
            if (bearerToken.startsWith("Bearer ")) {
                return bearerToken.substring(7);
            }
            return bearerToken;
        }
        return request.getParameter("access_token");
    }
}
