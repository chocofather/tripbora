package com.tripbora.repository.travelplan;

import com.tripbora.model.TravelPlanChatImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface TravelPlanChatImageMapper {
    int insert(TravelPlanChatImage image);
    List<TravelPlanChatImage> findByMessageIds(@Param("messageIds") List<Long> messageIds);
    TravelPlanChatImage findByIdAndMessage(@Param("id") Long id, @Param("messageId") Long messageId);
    boolean isReferenced(@Param("storageKey") String storageKey);
}
