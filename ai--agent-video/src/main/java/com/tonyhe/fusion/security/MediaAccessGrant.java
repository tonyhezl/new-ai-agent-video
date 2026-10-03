package com.tonyhe.fusion.security;

import lombok.Data;

/** A business resource that owns or references one local media URL. */
@Data
public class MediaAccessGrant {
    private String resourceType;
    private Long resourceId;
}
