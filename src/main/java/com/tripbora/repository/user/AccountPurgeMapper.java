package com.tripbora.repository.user;

import com.tripbora.model.AccountPurgeJob;
import com.tripbora.model.AccountPurgeTask;
import com.tripbora.model.AccountPurgeTaskType;
import com.tripbora.model.SocialProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 최종 파기 작업(account_purge_jobs / account_purge_tasks)과
 * DB row 를 지우기 전에 모아 두어야 하는 파일 경로 조회.
 */
@Mapper
public interface AccountPurgeMapper {

    /** 공개 댓글 사진은 보존하되 최종 탈퇴 시 갤러리 활용 동의를 철회한다. */
    int revokeReviewImageConsents(@Param("userId") Long userId);

    /** 회원당 한 건만 있는 파기 작업. 같은 회원을 두 번 처리하지 않도록 잠그고 본다. */
    AccountPurgeJob findJobByUserIdForUpdate(@Param("userId") Long userId);

    int insertJob(AccountPurgeJob job);

    /** 후처리 task 가 남아 있는 경우. 파일/외부 연동 worker 가 이어받는다. */
    int markJobDbDone(@Param("id") Long id,
                      @Param("dbCompletedAt") LocalDateTime dbCompletedAt);

    /** 후처리 task 가 한 건도 없어 DB 처리만으로 끝난 경우. */
    int markJobCompleted(@Param("id") Long id,
                         @Param("dbCompletedAt") LocalDateTime dbCompletedAt,
                         @Param("completedAt") LocalDateTime completedAt);

    /** 앞선 시도가 남긴 task 를 지우고 다시 수집하기 위한 정리. */
    int deleteTasksByJobId(@Param("purgeJobId") Long purgeJobId);

    int insertTask(AccountPurgeTask task);

    List<AccountPurgeTask> findTasksByJobId(@Param("purgeJobId") Long purgeJobId);

    AccountPurgeTask findTaskById(@Param("id") Long id);

    /**
     * 지금 실행할 수 있는 task. 전체를 읽지 않고 limit 만큼만 가져온다.
     *
     * @param provider null 이면 provider 를 따지지 않는다 (FILE_DELETE 는 provider 가 없다).
     */
    List<AccountPurgeTask> findReadyTasks(@Param("taskType") AccountPurgeTaskType taskType,
                                          @Param("provider") SocialProvider provider,
                                          @Param("currentTime") LocalDateTime currentTime,
                                          @Param("limit") int limit);

    /**
     * task 를 집어 든다. 시도 횟수를 올리고 다음 실행 가능 시각을 임대 기간만큼 미뤄 두어,
     * 같은 task 를 다른 worker 가 곧바로 다시 집지 않게 한다.
     * taskType/provider 조건이 함께 걸려 한 worker 가 다른 종류의 task 를 집을 수 없다.
     *
     * @return 1 이면 이 worker 가 처리 권한을 얻었고, 0 이면 그 사이 남이 가져갔거나 끝났다.
     */
    int claimTask(@Param("id") Long id,
                  @Param("taskType") AccountPurgeTaskType taskType,
                  @Param("provider") SocialProvider provider,
                  @Param("currentTime") LocalDateTime currentTime,
                  @Param("leaseUntil") LocalDateTime leaseUntil);

    int markTaskCompleted(@Param("id") Long id,
                          @Param("completedAt") LocalDateTime completedAt);

    int markTaskRetry(@Param("id") Long id,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("lastError") String lastError);

    /** 더 시도해도 소용없는 task. 행과 사유는 남겨 두고 자동 실행 대상에서만 뺀다. */
    int markTaskFailed(@Param("id") Long id,
                       @Param("lastError") String lastError);

    /**
     * 남은 task 가 하나도 없을 때만 job 을 끝낸다.
     * SOCIAL_UNLINK 가 남아 있거나 FAILED 가 섞여 있으면 DB_DONE 그대로 둔다.
     *
     * @return 1 이면 이번에 완료 처리했고, 0 이면 아직 남았거나 이미 끝난 job 이다.
     */
    int completeJobIfAllTasksDone(@Param("purgeJobId") Long purgeJobId,
                                  @Param("completedAt") LocalDateTime completedAt);

    /**
     * 다이어리가 쓰는 업로드 파일 경로. 대표 이미지와 페이지 PHOTO 요소, 그리고 다이어리에
     * 실제로 적용된 표지의 PHOTO 요소를 함께 준다.
     * STICKER/NOTE/TEXT 는 업로드 파일이 아니므로 여기에 들어오지 않는다.
     * 라이브러리 공유 사진은 회원 파일이 아니라 공유 자산이므로 들어오지 않는다.
     */
    List<String> findDiaryImageUrlsByUserId(@Param("userId") Long userId);

    /** 표지 디자인의 PHOTO 요소가 쓰는 업로드 파일 경로. */
    List<String> findCoverDesignImageUrlsByUserId(@Param("userId") Long userId);
}
