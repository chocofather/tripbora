package com.tripbora.config;

import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.processor.element.AbstractElementTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.util.EscapedAttributeUtils;

import java.util.Map;
import java.util.Set;

/**
 * 일반 href/src도 Spring의 내용 해시 URL 처리를 거치게 한다.
 * 공통 layout뿐 아니라 독립 페이지와 fragment에도 같은 계약을 적용한다.
 */
public final class StaticResourceDialect extends AbstractProcessorDialect {

    public StaticResourceDialect() {
        super("Bundled static resource URLs", "", 1000);
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        return Set.of(new AssetProcessor("link", "href"), new AssetProcessor("script", "src"));
    }

    private static final class AssetProcessor extends AbstractElementTagProcessor {
        private final String attribute;

        AssetProcessor(String tag, String attribute) {
            // Standard th:href/th:src (1000), if/unless and fragment insertion run first.
            super(TemplateMode.HTML, null, tag, false, null, false, 1100);
            this.attribute = attribute;
        }

        @Override
        protected void doProcess(ITemplateContext context, IProcessableElementTag tag,
                                 IElementTagStructureHandler structureHandler) {
            String url = tag.getAttributeValue(attribute);
            if (url == null || !(url.startsWith("/css/") || url.startsWith("/js/"))) return;
            url = EscapedAttributeUtils.unescapeAttribute(TemplateMode.HTML, url);
            // Keep functional query parameters, but retire the legacy manually maintained ?v=date.
            url = UriComponentsBuilder.fromUriString(url).replaceQueryParam("v").build(true).toUriString();
            // StandardLinkBuilder calls response.encodeURL -> ResourceUrlEncodingFilter.
            String versioned = context.buildLink(url, Map.of());
            structureHandler.setAttribute(attribute,
                    EscapedAttributeUtils.escapeAttribute(TemplateMode.HTML, versioned));
        }
    }
}
