package com.tonyhe.fusion.service.generation.strategy.impl.newapi;

import com.tonyhe.fusion.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NewAPI 视频协议路由器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NewApiVideoProtocolRouter {

    private final List<NewApiVideoProtocolAdapter> adapters;

    private volatile Map<String, NewApiVideoProtocolAdapter> adapterMap;

    public NewApiVideoProtocolAdapter resolve(NewApiVideoProtocolContext context) {
        Map<String, NewApiVideoProtocolAdapter> map = getAdapterMap();
        String protocol = context.metadata() != null ? context.metadata().effectiveProtocol() : "generic";
        NewApiVideoProtocolAdapter adapter = map.get(protocol);
        if (adapter != null) {
            return adapter;
        }

        if (!"generic".equals(protocol)) {
            log.warn("[NewApi Video] 未找到协议适配器，回退到通用协议: protocol={}, model={}",
                    protocol, context.model() != null ? context.model().getCode() : null);
        }

        adapter = map.get("generic");
        if (adapter != null) {
            return adapter;
        }

        throw new BusinessException("New API 缺少通用视频协议适配器");
    }

    private Map<String, NewApiVideoProtocolAdapter> getAdapterMap() {
        if (adapterMap == null) {
            synchronized (this) {
                if (adapterMap == null) {
                    Map<String, NewApiVideoProtocolAdapter> resolved = new LinkedHashMap<>();
                    for (NewApiVideoProtocolAdapter adapter : adapters) {
                        resolved.putIfAbsent(adapter.getProtocol(), adapter);
                    }
                    adapterMap = resolved;
                }
            }
        }
        return adapterMap;
    }
}