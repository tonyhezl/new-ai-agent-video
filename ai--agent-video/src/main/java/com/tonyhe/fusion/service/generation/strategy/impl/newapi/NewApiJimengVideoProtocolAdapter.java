package com.tonyhe.fusion.service.generation.strategy.impl.newapi;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * NewAPI 即梦协议适配器。
 * 当前先复用通用视频组包，后续即梦字段分叉时只需在此类扩展。
 */
@Component
@RequiredArgsConstructor
public class NewApiJimengVideoProtocolAdapter implements NewApiVideoProtocolAdapter {

    private final NewApiVideoProtocolSupport support;

    @Override
    public String getProtocol() {
        return "jimeng";
    }

    @Override
    public JSONObject buildSubmitBody(NewApiVideoProtocolContext context) {
        return support.buildGenericSubmitBody(context);
    }
}