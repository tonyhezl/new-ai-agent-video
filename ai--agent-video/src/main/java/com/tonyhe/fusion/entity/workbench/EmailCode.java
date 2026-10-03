package com.tonyhe.fusion.entity.workbench;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tonyhe.fusion.common.BaseEntity;
import lombok.*;

import java.time.LocalDateTime;

@TableName("afv_email_code")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailCode extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String purpose;

    private String code;

    @Builder.Default
    private Integer used = 0;

    private LocalDateTime expireAt;

    private String clientIp;
}
