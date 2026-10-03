package com.tonyhe.fusion.controller.auth;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tonyhe.fusion.common.BusinessException;
import com.tonyhe.fusion.common.CommonResult;
import com.tonyhe.fusion.controller.system.vo.LoginRespVO;
import com.tonyhe.fusion.entity.system.User;
import com.tonyhe.fusion.entity.team.TeamMember;
import com.tonyhe.fusion.entity.workbench.Guest;
import com.tonyhe.fusion.mapper.workbench.GuestMapper;
import com.tonyhe.fusion.security.TokenService;
import com.tonyhe.fusion.service.auth.EmailCodeService;
import com.tonyhe.fusion.service.system.MailService;
import com.tonyhe.fusion.service.team.TeamService;
import com.tonyhe.fusion.service.system.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import static com.tonyhe.fusion.common.CommonResult.success;

@Tag(name = "邮箱认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class EmailAuthController {

    private final EmailCodeService emailCodeService;
    private final MailService mailService;
    private final UserService userService;
    private final GuestMapper guestMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final TeamService teamService;

    @PostMapping("/email-code")
    @Operation(summary = "发送邮箱验证码")
    public CommonResult<Boolean> sendEmailCode(@Valid @RequestBody EmailCodeReqVO reqVO,
                                                HttpServletRequest request) {
        String ip = getClientIp(request);
        String code = emailCodeService.generateAndSave(reqVO.getEmail(), reqVO.getPurpose(), ip);

        String subject = switch (reqVO.getPurpose()) {
            case "REGISTER" -> "【织影】注册验证码";
            case "RESET_PASSWORD" -> "【织影】密码重置验证码";
            case "CHANGE_EMAIL" -> "【织影】更换邮箱验证码";
            default -> "【织影】验证码";
        };

        String content = String.format(
                "<div style='font-family: sans-serif; padding: 20px; max-width: 600px; margin: 0 auto;'>" +
                        "  <h2 style='color: #333;'>织影 · 验证码</h2>" +
                        "  <p>您的验证码是：</p>" +
                        "  <p style='font-size: 28px; font-weight: bold; letter-spacing: 4px; color: #000;'>%s</p>" +
                        "  <p style='color: #999; font-size: 12px;'>验证码 10 分钟内有效。如果您没有请求此操作，请忽略此邮件。</p>" +
                        "</div>",
                code);
        mailService.sendHtmlEmail(reqVO.getEmail(), subject, content);
        return success(true);
    }

    @PostMapping("/register/email")
    @Operation(summary = "邮箱注册")
    public CommonResult<LoginRespVO> registerByEmail(@Valid @RequestBody EmailRegisterReqVO reqVO) {
        String email = reqVO.getEmail().trim().toLowerCase();

        // 1. 校验验证码
        if (!emailCodeService.verify(email, "REGISTER", reqVO.getCode())) {
            throw new BusinessException(400, "验证码无效或已过期");
        }

        // 2. 校验邮箱唯一性
        User existing = userService.getByUsernameOrEmail(email);
        if (existing != null) {
            throw new BusinessException(400, "该邮箱已注册");
        }

        // 3. 生成 username（邮箱前缀 + 随机后缀）
        String prefix = email.split("@")[0];
        if (prefix.length() > 20) prefix = prefix.substring(0, 20);
        String username = prefix + "_" + IdUtil.fastSimpleUUID().substring(0, 6);

        // 4. 创建用户
        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(reqVO.getPassword()))
                .nickname(prefix)
                .email(email)
                .status(1)
                .creditBalance(0)
                .creditVersion(0)
                .freeQuotaUsed(0)
                .freeQuotaTotal(2)
                .build();
        userService.createUserDirectly(user);

        // 5. 绑定游客（不重复发放免费额度）
        if (StrUtil.isNotBlank(reqVO.getGuestId())) {
            Guest guest = guestMapper.selectOne(new LambdaQueryWrapper<Guest>()
                    .eq(Guest::getGuestId, reqVO.getGuestId()));
            if (guest != null && guest.getBoundUserId() == null) {
                guest.setBoundUserId(user.getId());
                guestMapper.updateById(guest);
                user.setGuestId(guest.getGuestId());
                userService.updateGuestId(user.getId(), guest.getGuestId());
                // 继承游客已用的免费次数
                user.setFreeQuotaUsed(guest.getFreeQuotaUsed());
                userService.updateFreeQuotaUsed(user.getId(), guest.getFreeQuotaUsed());
            }
        }

        // 6. 分配角色 + 团队
        userService.assignDefaultRoleAndTeam(user.getId());

        // 7. 返回 JWT
        Long currentTeamId = teamService.getCurrentTeamIdByUser(user.getId());
        TokenService.TokenPair tokenPair = tokenService.createToken(user.getId(), user.getUsername(), currentTeamId);

        return success(LoginRespVO.builder()
                .accessToken(tokenPair.getAccessToken())
                .refreshToken(tokenPair.getRefreshToken())
                .expiresIn(tokenPair.getExpiresIn())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build());
    }

    @PostMapping("/password/reset")
    @Operation(summary = "邮箱重置密码")
    public CommonResult<Boolean> resetPassword(@Valid @RequestBody EmailResetPasswordReqVO reqVO) {
        String email = reqVO.getEmail().trim().toLowerCase();

        // 1. 校验验证码
        if (!emailCodeService.verify(email, "RESET_PASSWORD", reqVO.getCode())) {
            throw new BusinessException(400, "验证码无效或已过期");
        }

        // 2. 查找用户
        User user = userService.getByUsernameOrEmail(email);
        if (user == null) {
            throw new BusinessException(400, "该邮箱未注册");
        }

        // 3. 重置密码
        userService.resetPassword(user.getId(), reqVO.getNewPassword());
        return success(true);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (StrUtil.isBlank(ip)) {
            ip = request.getHeader("X-Forwarded-For");
        }
        if (StrUtil.isBlank(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    @Data
    public static class EmailCodeReqVO {
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        private String email;

        @NotBlank(message = "用途不能为空")
        private String purpose;
    }

    @Data
    public static class EmailRegisterReqVO {
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        private String email;

        @NotBlank(message = "验证码不能为空")
        private String code;

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度为 6-32 位")
        private String password;

        private String guestId;
    }

    @Data
    public static class EmailResetPasswordReqVO {
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        private String email;

        @NotBlank(message = "验证码不能为空")
        private String code;

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度为 6-32 位")
        private String newPassword;
    }
}
