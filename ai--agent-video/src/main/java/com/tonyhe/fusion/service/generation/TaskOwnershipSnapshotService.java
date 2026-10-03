package com.tonyhe.fusion.service.generation;

import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.team.TeamMapper;
import com.tonyhe.fusion.service.team.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Normalizes asynchronous task ownership and verifies its parent before result writes. */
@Service
@RequiredArgsConstructor
public class TaskOwnershipSnapshotService {

    private static final int OWNER_TYPE_TEAM = 2;

    private final ProjectMapper projectMapper;
    private final TeamMapper teamMapper;
    private final TeamService teamService;

    public Long resolveTeamId(Long userId, Long projectId, Long requestedTeamId) {
        if (projectId != null) {
            Project project = projectMapper.selectById(projectId);
            if (project == null) throw new BusinessException(404, "生成任务所属项目不存在或已删除");
            if (Integer.valueOf(OWNER_TYPE_TEAM).equals(project.getOwnerType())) {
                return project.getOwnerId();
            }
        }
        if (requestedTeamId != null) return requestedTeamId;
        return userId != null ? teamService.getCurrentTeamIdByUser(userId) : null;
    }

    public void assertParentActive(Long projectId, Long teamId) {
        if (projectId != null && projectMapper.selectById(projectId) == null) {
            throw new BusinessException(409, "生成任务所属项目已删除，停止写入结果");
        }
        if (teamId != null && teamMapper.selectById(teamId) == null) {
            throw new BusinessException(409, "生成任务所属团队已删除，停止写入结果");
        }
    }
}
