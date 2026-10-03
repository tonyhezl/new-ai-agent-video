package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

import java.time.LocalDateTime;

@TableName("afv_workbench_image_task")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchImageTask extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;

    private Long userId;

    private String guestId;

    private Long modelId;

    private Long priceId;

    @Builder.Default
    private String qualityLevel = "standard";

    @Builder.Default
    private String aspectRatio = "1:1";

    @Builder.Default
    private Integer creditCost = 0;

    @Builder.Default
    private Integer isFreeUsed = 0;

    @Builder.Default
    private Integer billed = 0;

    private String prompt;

    private String negativePrompt;

    @Builder.Default
    private Integer status = 0;

    private String errorMsg;

    @Builder.Default
    private String source = "workbench";

    private String clientIp;

    private LocalDateTime completedAt;

    @Builder.Default
    private Long deletedId = 0L;
}
