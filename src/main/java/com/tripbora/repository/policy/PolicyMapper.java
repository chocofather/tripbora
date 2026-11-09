package com.tripbora.repository.policy;

import com.tripbora.model.PolicyTranslation;
import com.tripbora.model.PolicyVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * policy_versions / policy_translations 읽기 전용 조회.
 * 정책 본문 등록과 활성화는 DB 작업이라 여기에서 쓰지 않는다.
 */
@Mapper
public interface PolicyMapper {

    /** 사진 동의의 live 판정은 DB TIMESTAMP/정책 DATETIME과 같은 세션 시계를 사용한다. */
    LocalDateTime databaseWallTime();

    /** 공통 판정 전용: 과거 스냅샷과 명시적인 동의 기준 버전 확인. */
    PolicyVersion findVersionById(@Param("id") Long id);

    /** 명시 기준과 표시 버전 사이에 새 범위를 만든 개정이 있는지 확인한다. */
    boolean hasInterveningMaterialVersion(@Param("basisId") Long basisId,
                                          @Param("versionId") Long versionId);

    /**
     * 요청한 policy_type 들의 현재 사용 버전. 타입당 최대 한 행만 돌려준다.
     *
     * <p>is_active = 1, published_at IS NOT NULL, effective_at <= currentTime 을 모두 만족하는
     * 버전만 대상이다. 아직 활성화하지 않은 정책은 한 행도 나오지 않는다.
     *
     * @param policyTypes 조회할 policy_type 이름
     * @param currentTime 시행일 판정 기준 시각
     */
    List<PolicyVersion> findActiveVersions(
            @Param("policyTypes") List<String> policyTypes,
            @Param("currentTime") LocalDateTime currentTime);

    /**
     * 주어진 버전들의 번역 중 요청 locale 과 대체 locale 것만. 어느 쪽을 쓸지는 서비스가 고른다.
     *
     * @param policyVersionIds 대상 policy_versions.id
     * @param locale 화면 locale
     * @param fallbackLocale 요청 locale 번역이 없을 때 쓸 locale
     */
    List<PolicyTranslation> findTranslations(
            @Param("policyVersionIds") List<Long> policyVersionIds,
            @Param("locale") String locale,
            @Param("fallbackLocale") String fallbackLocale);
}
