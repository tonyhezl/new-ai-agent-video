package com.tonyhe.fusion.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tonyhe.fusion.entity.ai.AgentMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    @Select("SELECT COALESCE(MAX(message_order), 0) FROM afv_agent_message WHERE conversation_id = #{conversationId} AND deleted = false")
    int findMaxMessageOrder(String conversationId);

    @Select("""
            SELECT
                COALESCE(parent_tool_call_id, '__main_agent__') AS agentKey,
                COALESCE(NULLIF(parent_tool_call_id, ''), '主Agent') AS agentLabel,
                SUM(COALESCE(input_tokens, 0)) AS totalInputTokens,
                SUM(COALESCE(output_tokens, 0)) AS totalOutputTokens,
                SUM(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)) AS totalTokens,
                MAX(model_code) AS modelCode,
                COUNT(*) AS reasoningCount
            FROM afv_agent_message
            WHERE conversation_id = #{conversationId}
              AND deleted = false
              AND role = 'assistant'
              AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL)
            GROUP BY COALESCE(parent_tool_call_id, '__main_agent__'), COALESCE(NULLIF(parent_tool_call_id, ''), '主Agent')
            """)
    List<Map<String, Object>> selectTokenUsageByConversation(String conversationId);

    @Select("""
            SELECT
                SUM(COALESCE(input_tokens, 0)) AS totalInputTokens,
                SUM(COALESCE(output_tokens, 0)) AS totalOutputTokens,
                SUM(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)) AS totalTokens,
                COUNT(*) AS totalRequests
            FROM afv_agent_message m
            INNER JOIN afv_agent_conversation c ON m.conversation_id = c.conversation_id
            WHERE m.deleted = false
              AND m.role = 'assistant'
              AND (m.input_tokens IS NOT NULL OR m.output_tokens IS NOT NULL)
              AND c.user_id = #{userId}
            """)
    Map<String, Object> selectTokenUsageSummary(Long userId);

    @Select("""
            SELECT
                COALESCE(m.model_code, 'unknown') AS modelCode,
                SUM(COALESCE(m.input_tokens, 0)) AS totalInputTokens,
                SUM(COALESCE(m.output_tokens, 0)) AS totalOutputTokens,
                SUM(COALESCE(m.input_tokens, 0) + COALESCE(m.output_tokens, 0)) AS totalTokens,
                COUNT(*) AS requestCount
            FROM afv_agent_message m
            INNER JOIN afv_agent_conversation c ON m.conversation_id = c.conversation_id
            WHERE m.deleted = false
              AND m.role = 'assistant'
              AND (m.input_tokens IS NOT NULL OR m.output_tokens IS NOT NULL)
              AND c.user_id = #{userId}
            GROUP BY COALESCE(m.model_code, 'unknown')
            ORDER BY totalTokens DESC
            """)
    List<Map<String, Object>> selectTokenUsageByModel(Long userId);

    @Select("""
            SELECT
                c.conversation_id AS conversationId,
                c.title AS title,
                c.category AS category,
                c.agent_type AS agentType,
                c.project_id AS projectId,
                SUM(COALESCE(m.input_tokens, 0)) AS totalInputTokens,
                SUM(COALESCE(m.output_tokens, 0)) AS totalOutputTokens,
                SUM(COALESCE(m.input_tokens, 0) + COALESCE(m.output_tokens, 0)) AS totalTokens,
                COUNT(*) AS requestCount,
                MAX(m.create_time) AS lastUsedTime
            FROM afv_agent_message m
            INNER JOIN afv_agent_conversation c ON m.conversation_id = c.conversation_id
            WHERE m.deleted = false
              AND m.role = 'assistant'
              AND (m.input_tokens IS NOT NULL OR m.output_tokens IS NOT NULL)
              AND c.user_id = #{userId}
            GROUP BY c.conversation_id, c.title, c.category, c.agent_type, c.project_id
            ORDER BY totalTokens DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectTokenUsageByConversationTop(Long userId, int limit);

    @Select("""
            SELECT
                DATE(m.create_time) AS date,
                SUM(COALESCE(m.input_tokens, 0)) AS totalInputTokens,
                SUM(COALESCE(m.output_tokens, 0)) AS totalOutputTokens,
                SUM(COALESCE(m.input_tokens, 0) + COALESCE(m.output_tokens, 0)) AS totalTokens,
                COUNT(*) AS requestCount
            FROM afv_agent_message m
            INNER JOIN afv_agent_conversation c ON m.conversation_id = c.conversation_id
            WHERE m.deleted = false
              AND m.role = 'assistant'
              AND (m.input_tokens IS NOT NULL OR m.output_tokens IS NOT NULL)
              AND c.user_id = #{userId}
              AND m.create_time >= #{startDate}
            GROUP BY DATE(m.create_time)
            ORDER BY date ASC
            """)
    List<Map<String, Object>> selectTokenUsageDailyTrend(Long userId, java.time.LocalDateTime startDate);
}
