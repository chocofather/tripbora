package com.tripbora.service.comment;

import com.tripbora.repository.comment.ReviewPhotoAnalysisMapper;
import com.tripbora.service.file.FileUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** 원본 저장 트랜잭션과 독립적이다. 한 번에 5장, 실패는 최대 3회만 분석한다. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="review-photo.analysis.enabled",havingValue="true",matchIfMissing=true)
public class ReviewPhotoAnalysisWorker {
    private final ReviewPhotoAnalysisMapper mapper;
    private final ReviewPhotoAnalyzer analyzer;
    private final FileUploadService files;

    @Scheduled(fixedDelayString="${review-photo.analysis.delay-ms:10000}",initialDelayString="${review-photo.analysis.initial-delay-ms:30000}")
    public void runBatch() {
        try {
            mapper.expireExhausted(5);
            for(var job:mapper.findDue(5)) {
                String token=UUID.randomUUID().toString();
                if(mapper.claim(job.getId(),token)!=1) continue;
                try {
                    var path=files.commentFileForAnalysis(job.getImageUrl());
                    if (path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".webp")) {
                        mapper.completeHashOnly(job,token,analyzer.exactHash(path));
                        continue;
                    }
                    try {
                        var result=analyzer.analyze(path);
                        mapper.complete(job,token,result,ReviewPhotoAnalyzer.band(result.dHash(),0),ReviewPhotoAnalyzer.band(result.dHash(),1),
                                ReviewPhotoAnalyzer.band(result.dHash(),2),ReviewPhotoAnalyzer.band(result.dHash(),3));
                    } catch(java.io.IOException cannotDecode) {
                        // 품질 판독과 파일 동일성은 별개다. 읽을 수 있는 원본의 hash는 계속 제공한다.
                        mapper.completeHashOnly(job,token,analyzer.exactHash(path));
                    }
                } catch(Exception failure) { mapper.fail(job.getId(),token); }
            }
        } catch(Exception failure) {
            // 원본 리뷰 공개나 업로드 요청에 예외를 전파하지 않는다. 경로·개인 정보는 로그로 남기지 않는다.
            log.warn("리뷰 사진 분석 배치를 완료하지 못했습니다. 다음 배치에서 재시도합니다.");
        }
    }
}
