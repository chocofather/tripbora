package com.tripbora.repository.comment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReviewGalleryPriorityMapper {
    /** 소속·동의·공개 상태 잠금 확인 후 제출 버전이 일치하는 사진만 변경한다. */
    int update(@Param("imageId") Long imageId, @Param("priority") Integer priority,
               @Param("expectedVersion") long expectedVersion);
}
