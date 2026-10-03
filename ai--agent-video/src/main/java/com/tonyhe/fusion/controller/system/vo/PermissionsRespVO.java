package com.tonyhe.fusion.controller.system.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限接口响应 VO
 * <p>
 * 返回当前用户、角色、权限码、可见菜单、数据范围和权限版本。
 */
@Data
@Builder
public class PermissionsRespVO {

    private UserInfo user;

    private Set<String> permissions;

    private List<MenuVO> menus;

    private Map<String, String> dataScope;

    private Long permissionVersion;

    @Data
    @Builder
    public static class UserInfo {
        private Long id;
        private String username;
        private String nickname;
        private List<String> roles;
        private Long teamId;
    }

    @Data
    @Builder
    public static class MenuVO {
        private Long id;
        private String name;
        private String title;
        private String path;
        private String icon;
        private Integer sort;
        private List<MenuVO> children;
    }
}
