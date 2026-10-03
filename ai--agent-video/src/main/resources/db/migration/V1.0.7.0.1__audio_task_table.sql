-- 1. StoryboardItem 新增音频字段
ALTER TABLE afv_storyboard_item
    ADD COLUMN audio_url VARCHAR(1024) COMMENT '对白混音URL（该镜头所有对白拼合后的预览音频）',
    ADD COLUMN audio_status INT DEFAULT 0 COMMENT '0-未生成 1-生成中 2-已生成 3-失败';

-- 2. 音频任务表（一镜多句，每句一行）
CREATE TABLE afv_audio_task (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                 VARCHAR(64) NOT NULL UNIQUE COMMENT '业务任务ID（UUID）',
    project_id              BIGINT NOT NULL,
    user_id                 BIGINT,

    storyboard_id           BIGINT NOT NULL COMMENT '分镜ID',
    storyboard_episode_id   BIGINT NOT NULL COMMENT '分镜集ID',
    storyboard_item_id      BIGINT NOT NULL COMMENT '关联 StoryboardItem.id',
    seq                     INT NOT NULL DEFAULT 0 COMMENT '该镜头内第几句对白（从0开始）',

    text                    TEXT NOT NULL COMMENT '待合成文本',
    character_name          VARCHAR(100) COMMENT '角色名',
    character_asset_id      BIGINT COMMENT '匹配到的角色主资产ID',
    voice_config            TEXT COMMENT '音色配置 JSON',
    dialogue_type           VARCHAR(10) COMMENT '1-对白 3-画外音 5-环境描写',

    model_id                BIGINT COMMENT '关联 AiModel.id',
    model_code              VARCHAR(200),

    audio_url               VARCHAR(1024) COMMENT '生成的音频文件URL',
    duration_ms             INT COMMENT '音频时长（毫秒）',
    sample_rate             INT COMMENT '采样率',

    status                  INT DEFAULT 0 COMMENT '0-待处理 1-处理中 2-成功 3-失败',
    error_message           TEXT,

    retry_count             INT DEFAULT 0,
    parent_task_id          VARCHAR(64) COMMENT '重试时的原任务ID',

    deleted                 TINYINT NOT NULL DEFAULT 0,
    create_time             DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_project (project_id),
    INDEX idx_item (storyboard_item_id),
    INDEX idx_episode (storyboard_episode_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI配音任务';
