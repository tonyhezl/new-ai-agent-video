-- SMTP 邮件配置占位项（值留空，管理员在后台 SystemConfig 中填入实际参数）
INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'mail_smtp_host', '', 'SMTP 服务器地址（如 smtp.qq.com）', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'mail_smtp_host' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'mail_smtp_port', '465', 'SMTP 端口（SSL 通常 465，STARTTLS 通常 587）', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'mail_smtp_port' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'mail_username', '', 'SMTP 登录用户名（邮箱地址）', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'mail_username' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'mail_password', '', 'SMTP 登录密码或授权码', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'mail_password' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'mail_ssl', 'true', '是否启用 SSL', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'mail_ssl' AND deleted = 0);

INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'mail_from', '', '发件人地址（留空则用 mail_username）', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'mail_from' AND deleted = 0);

-- V免签通讯密钥占位项
INSERT INTO afv_system_config (config_key, config_value, remark, deleted, create_time, update_time)
SELECT 'vmq_comm_key', '', 'V免签通讯密钥（与 V免签后台设置的密钥一致）', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM afv_system_config WHERE config_key = 'vmq_comm_key' AND deleted = 0);
