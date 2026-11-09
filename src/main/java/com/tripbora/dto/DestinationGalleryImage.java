package com.tripbora.dto;

import com.tripbora.model.DestinationImage;
import lombok.Getter;
import org.springframework.beans.BeanUtils;

/** 상세 화면 전용. destination_images의 저장/대표 이미지 모델에는 리뷰 사진을 추가하지 않는다. */
@Getter
public class DestinationGalleryImage extends DestinationImage {
    private String galleryKey;
    private Long reviewImageId;
    private Long reviewId;

    public static DestinationGalleryImage administrator(DestinationImage image) {
        var item = new DestinationGalleryImage();
        BeanUtils.copyProperties(image, item);
        item.galleryKey = "admin:" + image.getId();
        return item;
    }

    public static DestinationGalleryImage traveler(ReviewGalleryPhoto photo) {
        var item = new DestinationGalleryImage();
        item.galleryKey = "review:" + photo.getId();
        item.reviewImageId = photo.getId();
        item.reviewId = photo.getCommentId();
        item.setDestinationId(photo.getDestinationId());
        item.setImageUrl(photo.getImageUrl());
        return item;
    }

    public boolean isTravelerPhoto() { return reviewId != null; }
    public String getReviewUrl() {
        return isTravelerPhoto() ? "/destinations/" + getDestinationId() + "?commentId=" + reviewId : null;
    }
    @Override public boolean isAttributionPresent() { return isTravelerPhoto() || super.isAttributionPresent(); }
}
