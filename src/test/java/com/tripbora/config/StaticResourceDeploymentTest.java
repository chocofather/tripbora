package com.tripbora.config;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fresh MVC contexts model two deployments, including Spring's resource-chain caches. */
class StaticResourceDeploymentTest {
    @TempDir Path directory;

    @Test
    void everyLiteralCssAndScriptReferenceInTemplatesUsesAnExistingHashedResource() throws Exception {
        var references = new LinkedHashSet<String>();
        var template = new StringBuilder("<html><head>");
        try (var paths = Files.walk(Path.of("src/main/resources/templates"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".html")).toList()) {
                for (var asset : Jsoup.parse(Files.readString(path)).select("link, script")) {
                    String attribute = asset.tagName().equals("link") ? "href" : "src";
                    String url = asset.attr(attribute);
                    if (url.isEmpty()) {
                        String expression = asset.attr("th:" + attribute);
                        if (expression.matches("@\\{/((css|js)/[^}]+)}")) {
                            url = expression.substring(2, expression.length() - 1);
                        }
                    }
                    if (!url.startsWith("/css/") && !url.startsWith("/js/")) continue;
                    if (references.add(url)) template.append(new org.jsoup.nodes.Element(asset.tagName())
                            .attr(attribute, url).outerHtml());
                }
            }
        }
        write("templates/inventory.html", template.append("</head></html>").toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader());
             var context = new AnnotationConfigWebApplicationContext()) {
            context.setClassLoader(loader); context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "custom.upload-path=" + directory);
            context.register(DeploymentApplication.class); context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(ResourceUrlEncodingFilter.class)).build();
            var html = mvc.perform(get("/inventory")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var assets = Jsoup.parse(html).select("link, script");
            assertThat(assets).hasSize(references.size());
            assertThat(assets.size()).isGreaterThan(100);
            for (var asset : assets) {
                var url = asset.hasAttr("href") ? asset.attr("href") : asset.attr("src");
                assertThat(url).matches("/(css|js)/.+-[a-f0-9]{32}\\.(css|js)(\\?.*)?");
                var response = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
                assertThat(response.getContentType()).isIn("text/css", "text/javascript", "application/javascript");
            }
        }
    }

    @Test
    void changedFilesProduceNewRenderedPathsAndOldHashesAreRejectedAfterRedeployment() throws Exception {
        write("templates/deployment.html", """
                <html xmlns:th="http://www.thymeleaf.org"><head>
                <link rel="stylesheet" href="/css/deploy.css?v=old">
                <script src="/js/deploy.js?a=one&amp;b=two&amp;v=old"></script>
                <script th:src="@{/js/deploy.js}"></script>
                <script src="https://cdn.example.test/library.js?v=1"></script>
                <script src="/webjars/example/1/library.js"></script>
                </head></html>
                """);
        write("static/css/deploy.css", "@import '/css/child.css'; body { color: red; background: url('/images/probe.svg'); }");
        write("static/css/child.css", "p { color: blue; }");
        write("static/images/probe.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        write("static/js/deploy.js", "window.deployment = 1;");
        var first = deployment(null);
        write("static/css/deploy.css", "@import '/css/child.css'; body { color: green; background: url('/images/probe.svg'); }");
        write("static/js/deploy.js", "window.deployment = 2;");
        var second = deployment(first);
        assertThat(second).hasSize(2);
        for (int i = 0; i < first.size(); i++) assertThat(second.get(i)).isNotEqualTo(first.get(i));
    }

    private List<String> deployment(List<String> oldUrls) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader());
             var context = new AnnotationConfigWebApplicationContext()) {
            context.setClassLoader(loader);
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "custom.upload-path=" + directory);
            context.register(DeploymentApplication.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(ResourceUrlEncodingFilter.class)).build();
            var html = mvc.perform(get("/deployment")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var doc = Jsoup.parse(html);
            var css = doc.selectFirst("link").attr("href");
            var js = doc.select("script").get(1).attr("src");
            assertThat(css).matches("/css/deploy-[a-f0-9]{32}\\.css");
            assertThat(js).matches("/js/deploy-[a-f0-9]{32}\\.js");
            assertThat(doc.select("script").get(0).attr("src")).isEqualTo(js + "?a=one&b=two");
            assertThat(doc.select("script").get(2).attr("src")).isEqualTo("https://cdn.example.test/library.js?v=1");
            assertThat(doc.select("script").get(3).attr("src")).isEqualTo("/webjars/example/1/library.js");
            var nested = Jsoup.parse(mvc.perform(get("/tripbora/deployment").contextPath("/tripbora"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(nested.selectFirst("link").attr("href")).isEqualTo("/tripbora" + css);
            assertThat(nested.select("script").get(1).attr("src")).isEqualTo("/tripbora" + js);
            var cssResponse = mvc.perform(get(css)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(cssResponse.getContentType()).isEqualTo("text/css");
            var cssText = cssResponse.getContentAsString();
            assertThat(cssText).containsPattern("/css/child-[a-f0-9]{32}\\.css")
                    .containsPattern("/images/probe-[a-f0-9]{32}\\.svg");
            for (var dependent : List.of(cssText.split("'")[1], cssText.split("'")[3]))
                mvc.perform(get(dependent)).andExpect(status().isOk());
            var jsResponse = mvc.perform(get(js)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(jsResponse.getContentType()).contains("javascript");
            assertThat(jsResponse.getContentAsString()).isEqualTo(Files.readString(directory.resolve("static/js/deploy.js")));
            if (oldUrls != null) for (var url : oldUrls) mvc.perform(get(url)).andExpect(status().isNotFound());
            return List.of(css, js);
        }
    }

    private void write(String name, String contents) throws Exception {
        var file = directory.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, contents);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({WebConfig.class, DeploymentController.class})
    static class DeploymentApplication {
        @Bean SpringResourceTemplateResolver templates() {
            var resolver = new SpringResourceTemplateResolver();
            resolver.setPrefix("classpath:/templates/"); resolver.setSuffix(".html");
            return resolver;
        }
        @Bean SpringTemplateEngine engine(SpringResourceTemplateResolver templates, StaticResourceDialect dialect) {
            var engine = new SpringTemplateEngine(); engine.setTemplateResolver(templates); engine.addDialect(dialect);
            return engine;
        }
        @Bean ThymeleafViewResolver views(SpringTemplateEngine engine) {
            var views = new ThymeleafViewResolver(); views.setTemplateEngine(engine); return views;
        }
    }

    @Controller
    static class DeploymentController {
        @GetMapping("/deployment") String deployment() { return "deployment"; }
        @GetMapping("/inventory") String inventory() { return "inventory"; }
    }
}
