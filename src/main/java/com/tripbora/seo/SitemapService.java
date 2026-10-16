package com.tripbora.seo;

import com.tripbora.model.TravelInfoContentType;
import com.tripbora.repository.seo.SitemapMapper;
import com.tripbora.web.CourseUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SitemapService {

    private static final List<String> STATIC_PATHS = List.of(
            "/", "/about", "/destinations", "/travel-info",
            "/travel-info?contentType=FESTIVAL", "/events", CourseUrls.LIST, "/board/list");

    private final SitemapMapper sitemapMapper;

    @Transactional(readOnly = true)
    public List<String> canonicalPaths() {
        List<String> paths = new ArrayList<>(STATIC_PATHS);
        sitemapMapper.findDestinationIds().forEach(id -> paths.add("/destinations/" + id));
        sitemapMapper.findTravelInfoEntries().forEach(info -> paths.add(
                TravelInfoContentType.FESTIVAL == info.contentType()
                        ? "/festivals/" + info.id()
                        : "/travel-info/" + info.id()));
        sitemapMapper.findEventIds().forEach(id -> paths.add("/events/" + id));
        sitemapMapper.findPostIds().forEach(id -> paths.add("/post/" + id));
        sitemapMapper.findCourseIds().forEach(id -> paths.add(CourseUrls.detail(id)));
        return List.copyOf(paths);
    }
}
