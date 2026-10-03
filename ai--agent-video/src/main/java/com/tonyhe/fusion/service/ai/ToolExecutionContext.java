package com.tonyhe.fusion.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行上下文
 * <p>
 * 封装工具执行时所需的用户身份和权限信息。
 * 通过方法参数显式传递，不依赖 SecurityContext（ThreadLocal），
 * 解决 Reactor 异步线程中上下文丢失的问题，同时兼容未来微服务化场景（可通过 HTTP/RPC 序列化传递）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionContext {

    /**
     * 当前操作用户 ID
     */
    private Long userId;

    /**
     * 所有者类型：1=个人（预留扩展团队/企业）
     */
    private Integer ownerType;

    /**
     * 所有者 ID（个人模式下等于 userId）
     */
    private Long ownerId;

    /**
     * 当前项目 ID（可选，用于读取项目级配置如分辨率、画面比例等）
     */
    private Long projectId;

    /**
     * 当前对话文本模型编码（可选，用于记录生成提示词的文本模型）
     */
    private String textModelCode;
}
