package com.tonyhe.fusion.service.generation.strategy.impl.newapi;

import cn.hutool.json.JSONObject;

/**
 * NewAPI 视频协议适配器。
 */
public interface NewApiVideoProtocolAdapter {

    String getProtocol();

    JSONObject buildSubmitBody(NewApiVideoProtocolContext context);
}