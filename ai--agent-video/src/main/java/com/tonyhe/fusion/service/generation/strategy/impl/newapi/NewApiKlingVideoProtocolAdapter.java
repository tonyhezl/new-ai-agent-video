package com.tonyhe.fusion.service.generation.strategy.impl.newapi;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * NewAPI 可灵协议适配器。
 * 当前先复用通用视频组包，后续可灵字段分叉时只需在此类扩展。
 */
@Component
@RequiredArgsConstructor
public class NewApiKlingVideoProtocolAdapter implements NewApiVideoProtocolAdapter {

    private final NewApiVideoProtocolSupport support;

    @Override
    public String getProtocol() {
        return "kling";
    }

    @Override
    public JSONObject buildSubmitBody(NewApiVideoProtocolContext context) {
        return support.buildGenericSubmitBody(context);
    }
}