package com.tripbora.controller.destination;

import com.tripbora.dto.DestinationGalleryPage;
import com.tripbora.service.destination.DestinationGalleryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 공개 상세와 같은 권한/정렬을 사용하는 읽기 전용 추가 조회. */
@RestController
@RequiredArgsConstructor
public class DestinationGalleryController {
    private final DestinationGalleryService gallery;

    @GetMapping("/destinations/{id}/gallery")
    public ResponseEntity<DestinationGalleryPage> gallery(@PathVariable Long id,
            @RequestParam(defaultValue = "ALL") String filter,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(gallery.page(id, filter, cursor));
    }
}
