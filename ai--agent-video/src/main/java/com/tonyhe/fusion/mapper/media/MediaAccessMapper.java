package com.tonyhe.fusion.mapper.media;

import com.tonyhe.fusion.security.MediaAccessGrant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Resolves a local media URL back to its owning business resources in one query. */
@Mapper
public interface MediaAccessMapper {

    @Select("""
            SELECT resource_type, resource_id
            FROM (
                SELECT 'PROJECT' resource_type, p.id resource_id
                FROM afv_project p
                WHERE p.deleted = 0 AND (p.cover_url = #{url} OR p.art_style_image_url = #{url})
                UNION ALL
                SELECT 'PROJECT', a.project_id
                FROM afv_asset a
                WHERE a.deleted = 0 AND a.project_id IS NOT NULL AND a.cover_url = #{url}
                UNION ALL
                SELECT 'PROJECT', a.project_id
                FROM afv_asset_item ai
                JOIN afv_asset a ON a.id = ai.asset_id AND a.deleted = 0
                WHERE ai.deleted = 0 AND a.project_id IS NOT NULL
                  AND (ai.image_url = #{url} OR ai.thumbnail_url = #{url})
                UNION ALL
                SELECT 'PROJECT', sb.project_id
                FROM afv_storyboard_item si
                JOIN afv_storyboard sb ON sb.id = si.storyboard_id AND sb.deleted = 0
                WHERE si.deleted = 0 AND sb.project_id IS NOT NULL AND (
                    si.image_url = #{url} OR si.reference_image_url = #{url}
                    OR si.video_url = #{url} OR si.generated_image_url = #{url}
                    OR si.first_frame_image_url = #{url} OR si.last_frame_image_url = #{url}
                    OR si.generated_video_url = #{url} OR si.audio_url = #{url})
                UNION ALL
                SELECT 'PROJECT', sb.project_id
                FROM afv_storyboard_episode se
                JOIN afv_storyboard sb ON sb.id = se.storyboard_id AND sb.deleted = 0
                WHERE se.deleted = 0 AND sb.project_id IS NOT NULL AND se.composed_video_url = #{url}
                UNION ALL
                SELECT CASE WHEN t.project_id IS NOT NULL THEN 'PROJECT'
                            WHEN t.owner_type = 2 THEN 'TEAM' ELSE 'USER' END,
                       CASE WHEN t.project_id IS NOT NULL THEN t.project_id
                            WHEN t.owner_type = 2 THEN t.owner_id ELSE t.user_id END
                FROM afv_image_item i
                JOIN afv_image_task t ON t.id = i.task_id AND t.deleted = 0
                WHERE i.deleted = 0 AND (i.image_url = #{url} OR i.thumbnail_url = #{url})
                UNION ALL
                SELECT CASE WHEN t.project_id IS NOT NULL THEN 'PROJECT'
                            WHEN t.owner_type = 2 THEN 'TEAM' ELSE 'USER' END,
                       CASE WHEN t.project_id IS NOT NULL THEN t.project_id
                            WHEN t.owner_type = 2 THEN t.owner_id ELSE t.user_id END
                FROM afv_video_item i
                JOIN afv_video_task t ON t.id = i.task_id AND t.deleted = 0
                WHERE i.deleted = 0 AND (i.video_url = #{url} OR i.cover_url = #{url}
                    OR i.first_frame_url = #{url} OR i.last_frame_url = #{url})
                UNION ALL
                SELECT CASE WHEN t.project_id IS NOT NULL THEN 'PROJECT' ELSE 'USER' END,
                       CASE WHEN t.project_id IS NOT NULL THEN t.project_id ELSE t.user_id END
                FROM afv_audio_task t
                WHERE t.deleted = 0 AND t.audio_url = #{url}
                UNION ALL
                SELECT 'POV', s.id
                FROM afv_pov_story s
                WHERE s.deleted = 0 AND s.final_video_url = #{url}
                UNION ALL
                SELECT 'POV', s.id
                FROM afv_pov_shot ps
                JOIN afv_pov_story s ON s.id = ps.story_id AND s.deleted = 0
                WHERE ps.deleted = 0 AND (ps.image_url = #{url} OR ps.video_url = #{url} OR ps.audio_url = #{url})
                UNION ALL
                SELECT 'POV', s.id
                FROM afv_pov_asset pa
                JOIN afv_pov_story s ON s.id = pa.story_id AND s.deleted = 0
                WHERE pa.deleted = 0 AND pa.reference_audio_url = #{url}
                UNION ALL
                SELECT 'POV', s.id
                FROM afv_pov_asset_item pai
                JOIN afv_pov_asset pa ON pa.id = pai.asset_id AND pa.deleted = 0
                JOIN afv_pov_story s ON s.id = pa.story_id AND s.deleted = 0
                WHERE pai.deleted = 0 AND pai.image_url = #{url}
                UNION ALL
                SELECT 'USER', u.id
                FROM sys_user u
                WHERE u.deleted = 0 AND u.avatar = #{url}
            ) media_grants
            WHERE resource_id IS NOT NULL
            LIMIT 50
            """)
    List<MediaAccessGrant> findGrantsByUrl(@Param("url") String url);
}
