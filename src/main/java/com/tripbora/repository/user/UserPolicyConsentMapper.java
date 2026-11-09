package com.tripbora.repository.user;

import com.tripbora.model.UserPolicyConsent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * user_policy_consents 는 동의 사실을 증빙하는 append-only 기록이다.
 * 보관기간이 끝난 탈퇴 회원의 이력을 지우는 것 외에는 수정하거나 덮어쓰지 않는다.
 */
@Mapper
public interface UserPolicyConsentMapper {
    /** 나중의 DECLINE이 과거 이용허락 자체를 철회하지는 않으므로 마지막 긍정 동의를 별도 조회한다. */
    UserPolicyConsent findLatestAgreementForType(@Param("userId") Long userId,
                                                @Param("policyType") String policyType,
                                                @Param("asOf") LocalDateTime asOf);
    /** 이력 없음과 다른 기준의 거절을 구분하기 위한 종류 전체의 최신 한 건. */
    UserPolicyConsent findLatestForType(@Param("userId") Long userId,
                                       @Param("policyType") String policyType,
                                       @Param("asOf") LocalDateTime asOf);
    /** 같은 기준의 표시 버전들을 합쳐 최신 결정 한 건만 조회한다. 전체 이력을 적재하지 않는다. */
    UserPolicyConsent findLatestForBasis(@Param("userId") Long userId,
                                        @Param("policyType") String policyType,
                                        @Param("basisId") Long basisId,
                                        @Param("asOf") LocalDateTime asOf);

    /** 종류 전체의 명시 철회 또는 의미 불명인 과거 false 중 최신 한 건. */
    UserPolicyConsent findLatestStopForType(@Param("userId") Long userId,
                                           @Param("policyType") String policyType,
                                           @Param("asOf") LocalDateTime asOf);

    /** 다른 기준의 최신 결정이 동의인 경우만 증거 한 건을 반환한다. */
    UserPolicyConsent findLatestAgreementOnOtherBasis(@Param("userId") Long userId,
                                                     @Param("policyType") String policyType,
                                                     @Param("basisId") Long basisId,
                                                     @Param("asOf") LocalDateTime asOf);
    UserPolicyConsent findLatestForUpdate(@Param("userId") Long userId,
                                         @Param("policyVersionId") Long policyVersionId);

    int revokeGalleryImages(@Param("userId") Long userId);

    /**
     * 동의 결정 한 건 기록. 거절(agreed = false)도 결정이라 그대로 INSERT 한다.
     * 같은 정책을 다시 결정하면 새 행이 쌓인다. UPDATE/UPSERT 를 쓰지 않는다.
     *
     * @return 저장한 행 수
     */
    int insertConsent(UserPolicyConsent consent);

    /**
     * 보관기간이 끝난 탈퇴 회원의 id. 최종 탈퇴가 끝난(deleted_at) 회원만 대상이다.
     *
     * @param retentionCutoff 이 시각 이전에 최종 탈퇴가 끝난 회원만 대상
     * @param limit 한 번에 가져올 회원 수
     */
    List<Long> findConsentRetentionExpiredUserIds(
            @Param("retentionCutoff") LocalDateTime retentionCutoff,
            @Param("limit") int limit);

    /**
     * 한 회원의 동의 이력 전체 삭제. 날짜별로 골라 지우지 않는다.
     * 목록을 읽은 뒤 상태가 바뀌었을 수 있으므로 보관기간 조건을 다시 확인한다.
     *
     * @return 지운 행 수
     */
    int deleteConsentsOfRetentionExpiredUser(
            @Param("userId") Long userId,
            @Param("retentionCutoff") LocalDateTime retentionCutoff);
}
