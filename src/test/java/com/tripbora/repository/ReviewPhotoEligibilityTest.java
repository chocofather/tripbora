package com.tripbora.repository;

import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** DB 연결 없이 실제 MyBatis XML의 SQL·바인딩 경계를 검증한다. */
class ReviewPhotoEligibilityTest {
    BoundSql comparison() throws Exception {
        var config=CanonicalPhotoConsentMapperTest.configuration();
        return config.getMappedStatement("com.tripbora.repository.comment.ReviewPhotoAnalysisMapper.groupPage")
                .getBoundSql(Map.of("destinationId",10L,"imageId",8L,"kind","exact","offset",0L,"limit",101));
    }
    @Test void comparisonUsesTheDatabaseWallClockLikeTheRuntimePhotoConsentService() throws Exception {
        var bound=comparison();
        assertThat(bound.getSql()).contains("CURRENT_TIMESTAMP(6)");
        assertThat(bound.hasAdditionalParameter("eligibilityTime")).isFalse();
        assertThat(bound.getParameterMappings()).noneMatch(parameter -> parameter.getProperty().equals("currentTime"));
    }
    @Test void comparisonExposesSpecificRestrictionReasonsInsteadOfOneBoolean() throws Exception {
        assertThat(comparison().getSql()).contains("AS eligibility_reason","PHOTO_CONSENT_MISSING","PHOTO_CONSENT_WITHDRAWN",
                "PHOTO_CONSENT_INVALID","POLICY_UNAVAILABLE","REVIEW_HIDDEN","REVIEW_DELETED",
                "MEMBER_RESTRICTED","DESTINATION_PRIVATE","REPLY_NOT_REVIEW");
    }
}
