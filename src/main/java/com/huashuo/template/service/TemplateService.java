package com.huashuo.template.service;

import com.huashuo.template.vo.TemplateCreateRequest;
import com.huashuo.template.vo.TemplateItem;

import java.util.List;
import java.util.OptionalLong;

public interface TemplateService {

    List<TemplateItem> listTemplates(OptionalLong viewerUserId, String scope, String keyword, String sort, String tag);

    TemplateItem getTemplateForViewer(Long templateId, OptionalLong viewerUserId);

    TemplateItem createTemplate(OptionalLong viewerUserId, TemplateCreateRequest request);

    TemplateItem publishTemplate(Long templateId, OptionalLong viewerUserId);

    TemplateItem forkTemplate(Long templateId, OptionalLong viewerUserId);
}

