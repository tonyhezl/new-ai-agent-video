package com.tonyhe.fusion.service.media;

import com.tonyhe.fusion.entity.asset.Asset;
import com.tonyhe.fusion.entity.asset.AssetItem;
import com.tonyhe.fusion.entity.project.Project;
import com.tonyhe.fusion.entity.storyboard.Storyboard;
import com.tonyhe.fusion.entity.storyboard.StoryboardItem;
import com.tonyhe.fusion.mapper.asset.AssetItemMapper;
import com.tonyhe.fusion.mapper.asset.AssetMapper;
import com.tonyhe.fusion.mapper.project.ProjectMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardItemMapper;
import com.tonyhe.fusion.mapper.storyboard.StoryboardMapper;
import com.tonyhe.fusion.service.project.ProjectService;
import com.tonyhe.fusion.service.system.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaLibraryServiceTests {

    @Mock
    private StoryboardItemMapper storyboardItemMapper;
    @Mock
    private StoryboardMapper storyboardMapper;
    @Mock
    private AssetItemMapper assetItemMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private UserService userService;
    @Mock
    private ProjectService projectService;

    @InjectMocks
    private MediaLibraryService mediaLibraryService;

    private static final Long ADMIN_USER_ID = 1L;
    private static final Long NORMAL_USER_ID = 2L;
    private static final Long OTHER_USER_ID = 3L;

    @Test
    void listMediaReturnsEmptyForNonAdminWithNoAccessibleProjects() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        when(projectService.listAccessibleByUser(NORMAL_USER_ID)).thenReturn(List.of());

        Map<String, Object> result = mediaLibraryService.listMedia(null, null, null, 1, 60, NORMAL_USER_ID);

        assertThat(result.get("total")).isEqualTo(0L);
        assertThat((List<?>) result.get("records")).isEmpty();
    }

    @Test
    void listMediaReturnsEmptyForNonAdminRequestingInaccessibleProject() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        Project ownProject = Project.builder().id(100L).build();
        when(projectService.listAccessibleByUser(NORMAL_USER_ID)).thenReturn(List.of(ownProject));

        Map<String, Object> result = mediaLibraryService.listMedia(999L, null, null, 1, 60, NORMAL_USER_ID);

        assertThat(result.get("total")).isEqualTo(0L);
        assertThat((List<?>) result.get("records")).isEmpty();
    }

    @Test
    void listMediaForAdminDoesNotFilter() {
        when(userService.isAdmin(ADMIN_USER_ID)).thenReturn(true);

        // Admin should not have projectService.listAccessibleByUser called
        // Use source=asset + mediaType=video to avoid all mapper calls (asset returns empty for video)
        mediaLibraryService.listMedia(null, "video", "asset", 1, 60, ADMIN_USER_ID);

        verify(projectService, never()).listAccessibleByUser(any());
    }

    @Test
    void batchClearMediaSkipsEntriesFromInaccessibleProjects() {
        when(userService.isAdmin(NORMAL_USER_ID)).thenReturn(false);
        Project ownProject = Project.builder().id(100L).build();
        when(projectService.listAccessibleByUser(NORMAL_USER_ID)).thenReturn(List.of(ownProject));

        // Entry from accessible project (100L)
        StoryboardItem sbItem = StoryboardItem.builder().id(10L).storyboardId(20L).build();
        when(storyboardItemMapper.selectById(10L)).thenReturn(sbItem);
        Storyboard sb = Storyboard.builder().id(20L).projectId(100L).build();
        when(storyboardMapper.selectById(20L)).thenReturn(sb);

        // Entry from inaccessible project (200L)
        StoryboardItem otherItem = StoryboardItem.builder().id(11L).storyboardId(21L).build();
        when(storyboardItemMapper.selectById(11L)).thenReturn(otherItem);
        Storyboard otherSb = Storyboard.builder().id(21L).projectId(200L).build();
        when(storyboardMapper.selectById(21L)).thenReturn(otherSb);

        when(storyboardItemMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> entry1 = new HashMap<>();
        entry1.put("source", "storyboard");
        entry1.put("entityId", 10L);
        entry1.put("field", "generatedImageUrl");

        Map<String, Object> entry2 = new HashMap<>();
        entry2.put("source", "storyboard");
        entry2.put("entityId", 11L);
        entry2.put("field", "generatedImageUrl");

        int cleared = mediaLibraryService.batchClearMedia(List.of(entry1, entry2), NORMAL_USER_ID);

        // Only entry1 should be cleared (project 100L is accessible)
        assertThat(cleared).isEqualTo(1);
        verify(storyboardItemMapper, times(1)).update(any(), any());
    }

    @Test
    void batchClearMediaForAdminClearsAll() {
        when(userService.isAdmin(ADMIN_USER_ID)).thenReturn(true);

        Map<String, Object> entry = new HashMap<>();
        entry.put("source", "asset");
        entry.put("entityId", 50L);
        entry.put("field", "imageUrl");

        when(assetItemMapper.update(any(), any())).thenReturn(1);

        int cleared = mediaLibraryService.batchClearMedia(List.of(entry), ADMIN_USER_ID);

        assertThat(cleared).isEqualTo(1);
        // Admin should not have projectService.listAccessibleByUser called
        verify(projectService, never()).listAccessibleByUser(any());
    }

    @Test
    void batchClearMediaWithoutUserIdDoesNoVerification() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("source", "storyboard");
        entry.put("entityId", 10L);
        entry.put("field", "generatedImageUrl");

        when(storyboardItemMapper.update(any(), any())).thenReturn(1);

        int cleared = mediaLibraryService.batchClearMedia(List.of(entry));

        assertThat(cleared).isEqualTo(1);
        verify(userService, never()).isAdmin(any());
    }
}
