package com.tripbora.service.user;

import com.tripbora.model.AccountPurgeJob;
import com.tripbora.model.AccountPurgeJobStatus;
import com.tripbora.model.AccountPurgeTask;
import com.tripbora.model.SocialAccount;
import com.tripbora.model.SocialProvider;
import com.tripbora.model.User;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.diary.DiaryCoverDesignMapper;
import com.tripbora.repository.diary.DiaryMapper;
import com.tripbora.repository.translation.GoogleTranslationDailyUsageMapper;
import com.tripbora.repository.user.AccountPurgeMapper;
import com.tripbora.repository.user.AccountRecoveryTokenMapper;
import com.tripbora.repository.user.SocialAccountMapper;
import com.tripbora.repository.user.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 회원 한 명의 최종 파기를 한 트랜잭션에서 끝낸다.
 *
 * <p>순서가 중요하다. DB row 를 지우고 나면 다시 알아낼 수 없는 파일 경로와 provider 식별값을
 * 먼저 account_purge_tasks 로 옮겨 두고, 그 다음에 개인 데이터를 지운다.
 * 중간에 어디서 실패하든 job/task 생성과 삭제가 함께 롤백되므로, 그 회원은 여전히
 * WITHDRAWAL_PENDING 으로 남아 다음 배치에서 다시 선택된다.
 *
 * <p>파일 삭제와 provider unlink 는 여기서 실행하지 않는다. task 로 기록만 한다.
 */
@Service
@RequiredArgsConstructor
public class AccountPurgeTransactionService {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeTransactionService.class);

    /** 서버 배치만으로 연결 해제를 시도할 수 있는 provider. 나머지는 우리 DB 연결만 끊는다. */
    private static final SocialProvider UNLINKABLE_PROVIDER = SocialProvider.KAKAO;

    private final UserMapper userMapper;
    private final AccountPurgeMapper accountPurgeMapper;
    private final AccountAnonymizationService accountAnonymizationService;
    private final DiaryMapper diaryMapper;
    private final DiaryCoverDesignMapper diaryCoverDesignMapper;
    private final AccountRecoveryTokenMapper accountRecoveryTokenMapper;
    private final GoogleTranslationDailyUsageMapper googleTranslationDailyUsageMapper;
    private final SocialAccountMapper socialAccountMapper;

    /**
     * 자동 배치가 쓰는 기본 파기. provider 연결 해제 정책도 기존 그대로다.
     *
     * <p>같은 클래스 안에서 아래 메서드를 부르면 프록시를 지나지 않으므로
     * 트랜잭션 경계는 여기에도 선언해 둔다.
     *
     * @return 파기를 수행했으면 결과, 대상이 아니면 {@link PurgeOutcome#skipped()}
     */
    @Transactional
    public PurgeOutcome purgeOne(Long userId, LocalDateTime currentTime) {
        return purgeOne(userId, currentTime, AccountPurgeOptions.scheduled());
    }

    /**
     * @param options 파기 절차는 같고 provider 연결 해제 정책만 달라진다
     * @return 파기를 수행했으면 결과, 대상이 아니면 {@link PurgeOutcome#skipped()}
     */
    @Transactional
    public PurgeOutcome purgeOne(Long userId, LocalDateTime currentTime,
                                 AccountPurgeOptions options) {
        if (userId == null || currentTime == null) {
            return PurgeOutcome.skipped();
        }
        AccountPurgeOptions purgeOptions =
                options == null ? AccountPurgeOptions.scheduled() : options;

        // 복구(AccountRecoveryService)와 같은 users 행을 두고 경쟁한다.
        // 복구가 먼저 ACTIVE 로 되돌렸다면 여기서 null 이 되어 아무것도 하지 않는다.
        User target = userMapper.findPurgeTargetByIdForUpdate(userId, currentTime);
        if (target == null) {
            return PurgeOutcome.skipped();
        }

        // 사진 관리도 같은 users 행을 먼저 잠그므로 심사·재동의와 최종 탈퇴가 직렬화된다.
        accountPurgeMapper.revokeReviewImageConsents(userId);

        AccountPurgeJob job = openJob(userId);

        // 1) 지우기 전에 후처리에 필요한 값부터 옮겨 둔다.
        int fileTasks = recordFileDeleteTasks(job.getId(), userId, target.getProfileImage());
        int unlinkTasks = recordSocialUnlinkTasks(job.getId(), userId, purgeOptions);
        int taskCount = fileTasks + unlinkTasks;

        // 2) 개인 데이터 삭제. 공개 콘텐츠와 운영 기록은 건드리지 않는다.
        accountAnonymizationService.clearPersonalTraces(userId);
        diaryMapper.deleteAllByUserId(userId);
        diaryCoverDesignMapper.deleteAllByUserId(userId);
        accountRecoveryTokenMapper.deleteAllByUserId(userId);
        googleTranslationDailyUsageMapper.deleteAllByUserId(userId);
        socialAccountMapper.deleteAllByUserId(userId);

        // 3) 회원 익명화. WHERE 가 파기 대상 조건을 다시 확인하므로 0 이면 중단한다.
        int finalized = userMapper.finalizeWithdrawal(
                userId,
                accountAnonymizationService.anonymizedEmail(userId),
                accountAnonymizationService.anonymizedNickname(),
                UserStatus.DEACTIVATED,
                currentTime,
                currentTime);
        if (finalized != 1) {
            throw new IllegalStateException("최종 탈퇴 처리를 완료하지 못했습니다. userId=" + userId);
        }

        // 4) 후처리가 없으면 DB 처리만으로 끝이다.
        if (taskCount == 0) {
            accountPurgeMapper.markJobCompleted(job.getId(), currentTime, currentTime);
            return new PurgeOutcome(true, job.getId(), 0, AccountPurgeJobStatus.COMPLETED);
        }
        accountPurgeMapper.markJobDbDone(job.getId(), currentTime);
        return new PurgeOutcome(true, job.getId(), taskCount, AccountPurgeJobStatus.DB_DONE);
    }

    /**
     * 회원당 job 은 하나뿐이다(user_id UNIQUE).
     * 정상 흐름에서는 새로 만들지만, 앞선 시도가 남긴 job 이 있으면 그것을 이어 쓰고
     * 그때 쌓였던 task 는 지우고 다시 모은다. 같은 파일을 두 번 지우려 하지 않기 위해서다.
     */
    private AccountPurgeJob openJob(Long userId) {
        AccountPurgeJob existing = accountPurgeMapper.findJobByUserIdForUpdate(userId);
        if (existing != null) {
            log.warn("Reusing an existing account purge job: userId={}, purgeJobId={}, status={}",
                    userId, existing.getId(), existing.getStatus());
            accountPurgeMapper.deleteTasksByJobId(existing.getId());
            return existing;
        }

        AccountPurgeJob job = new AccountPurgeJob();
        job.setUserId(userId);
        job.setStatus(AccountPurgeJobStatus.PENDING);
        accountPurgeMapper.insertJob(job);
        if (job.getId() == null) {
            throw new IllegalStateException("파기 작업을 만들지 못했습니다. userId=" + userId);
        }
        return job;
    }

    /**
     * 프로필 이미지와 다이어리 업로드 파일 경로를 task 로 남긴다.
     * 허용 업로드 폴더가 아닌 값(스티커 같은 정적 리소스 포함)은 아예 task 로 만들지 않는다.
     */
    private int recordFileDeleteTasks(Long purgeJobId, Long userId, String profileImage) {
        List<String> candidates = new ArrayList<>();
        candidates.add(profileImage);
        candidates.addAll(accountPurgeMapper.findDiaryImageUrlsByUserId(userId));
        candidates.addAll(accountPurgeMapper.findCoverDesignImageUrlsByUserId(userId));

        // 같은 파일이 여러 번 참조돼도 job 안에서는 한 번만 남긴다.
        Set<String> deletable = new LinkedHashSet<>();
        int rejected = 0;
        for (String candidate : candidates) {
            String normalized = AccountPurgeFileTargets.normalizeDeletable(candidate);
            if (normalized == null) {
                if (candidate != null && !candidate.isBlank()) {
                    rejected++;
                }
                continue;
            }
            deletable.add(normalized);
        }
        if (rejected > 0) {
            // 경로 자체는 남기지 않는다. 개수만으로 충분히 추적할 수 있다.
            log.info("Skipped non-managed file references while planning purge: userId={}, count={}",
                    userId, rejected);
        }

        for (String targetValue : deletable) {
            accountPurgeMapper.insertTask(AccountPurgeTask.fileDelete(purgeJobId, targetValue));
        }
        return deletable.size();
    }

    /**
     * 서버 배치만으로 연결 해제를 시도할 수 있는 provider 만 task 로 남긴다.
     * 나머지 provider 는 우리 DB 의 social_accounts 행만 지운다.
     *
     * <p>방금 그 provider 로 본인 인증을 하고 곧바로 새로 가입할 참이면
     * 그 provider 의 연결까지 끊으면 안 되므로 task 를 만들지 않는다.
     */
    private int recordSocialUnlinkTasks(Long purgeJobId, Long userId,
                                        AccountPurgeOptions options) {
        List<SocialAccount> accounts = socialAccountMapper.findAllByUserId(userId);
        if (accounts == null || accounts.isEmpty()) {
            return 0;
        }

        int created = 0;
        for (SocialAccount account : accounts) {
            if (account == null || account.getProvider() != UNLINKABLE_PROVIDER) {
                continue;
            }
            if (options.suppressesUnlinkFor(account.getProvider())) {
                log.info("Social unlink task skipped for the provider used to re-authenticate:"
                        + " userId={}, provider={}", userId, account.getProvider());
                continue;
            }
            String providerUserId = account.getProviderUserId() == null
                    ? null : account.getProviderUserId().strip();
            if (providerUserId == null || providerUserId.isEmpty()) {
                // 식별값이 없으면 나중에 실행할 수 없는 task 가 되므로 만들지 않는다.
                log.warn("Social unlink task skipped for a blank provider user id: userId={}, provider={}",
                        userId, account.getProvider());
                continue;
            }
            accountPurgeMapper.insertTask(AccountPurgeTask.socialUnlink(
                    purgeJobId, account.getProvider(), providerUserId));
            created++;
        }
        return created;
    }

    /** 배치 로그에 쓰는 처리 결과. 민감한 값은 담지 않는다. */
    public record PurgeOutcome(
            boolean purged,
            Long purgeJobId,
            int taskCount,
            AccountPurgeJobStatus jobStatus
    ) {
        public static PurgeOutcome skipped() {
            return new PurgeOutcome(false, null, 0, null);
        }
    }
}
