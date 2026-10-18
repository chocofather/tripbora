package com.tripbora.service.travelplan;

import com.tripbora.repository.travelplan.TravelPlanChatImageMapper;
import com.tripbora.service.file.TravelPlanChatImageStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 삭제 메시지/삭제 방/중단 업로드의 파일을 24시간 유예 후 제한된 양씩 정리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TravelPlanChatImageCleanup {
    private final TravelPlanChatImageMapper images;
    private final TravelPlanChatImageStorage storage;

    @Scheduled(fixedDelay=3600000, initialDelay=60000)
    public void clean() {
        for (String key : storage.staleKeys(200)) {
            try {
                // DB 확인 실패는 파일을 지울 이유가 아니다. 다음 주기에 다시 확인한다.
                if (!images.isReferenced(key)) storage.delete(key);
            } catch (RuntimeException exception) { log.warn("채팅 사진 정리를 다음 주기에 다시 시도합니다."); }
        }
    }
}
