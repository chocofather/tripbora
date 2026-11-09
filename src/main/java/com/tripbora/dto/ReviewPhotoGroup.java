package com.tripbora.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import java.util.List;

@Getter
@RequiredArgsConstructor
public class ReviewPhotoGroup {
    private final ReviewImageAdminDto review;
    private final List<ReviewImageAdminDto> photos;
    public long count(String status) { return photos.stream().filter(p -> status.equals(p.getGalleryStatus())).count(); }
    public long approvablePendingCount() { return photos.stream().filter(p -> "PENDING".equals(p.getGalleryStatus()) && p.isApprovable()).count(); }
}
