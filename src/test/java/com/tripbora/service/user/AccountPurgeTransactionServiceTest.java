package com.tripbora.service.user;

import com.tripbora.model.AccountPurgeJob;
import com.tripbora.model.AccountPurgeJobStatus;
import com.tripbora.model.AccountPurgeTask;
import com.tripbora.model.AccountPurgeTaskStatus;
import com.tripbora.model.AccountPurgeTaskType;
import com.tripbora.model.SocialAccount;
import com.tripbora.model.SocialProvider;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.diary.DiaryCoverDesignMapper;
import com.tripbora.repository.diary.DiaryMapper;
import com.tripbora.repository.translation.GoogleTranslationDailyUsageMapper;
import com.tripbora.repository.user.AccountPurgeMapper;
import com.tripbora.repository.user.AccountRecoveryTokenMapper;
import com.tripbora.repository.user.SocialAccountMapper;
import com.tripbora.repository.user.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountPurgeTransactionServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 11, 3, 0);

    @Mock private UserMapper userMapper;
    @Mock private AccountPurgeMapper accountPurgeMapper;
    @Mock private AccountAnonymizationService accountAnonymizationService;
    @Mock private DiaryMapper diaryMapper;
    @Mock private DiaryCoverDesignMapper diaryCoverDesignMapper;
    @Mock private AccountRecoveryTokenMapper accountRecoveryTokenMapper;
    @Mock private GoogleTranslationDailyUsageMapper googleTranslationDailyUsageMapper;
    @Mock private SocialAccountMapper socialAccountMapper;

    private AccountPurgeTransactionService service;

    @BeforeEach
    void setUp() {
        service = new AccountPurgeTransactionService(
                userMapper, accountPurgeMapper, accountAnonymizationService,
                diaryMapper, diaryCoverDesignMapper, accountRecoveryTokenMapper,
                googleTranslationDailyUsageMapper, socialAccountMapper);

        when(userMapper.findPurgeTargetByIdForUpdate(USER_ID, NOW)).thenReturn(target(null));
        when(accountPurgeMapper.findJobByUserIdForUpdate(USER_ID)).thenReturn(null);
        doAnswer(invocation -> {
            invocation.getArgument(0, AccountPurgeJob.class).setId(100L);
            return 1;
        }).when(accountPurgeMapper).insertJob(any(AccountPurgeJob.class));
        when(accountPurgeMapper.findDiaryImageUrlsByUserId(USER_ID)).thenReturn(List.of());
        when(accountPurgeMapper.findCoverDesignImageUrlsByUserId(USER_ID)).thenReturn(List.of());
        when(socialAccountMapper.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(accountAnonymizationService.anonymizedEmail(USER_ID))
                .thenReturn("withdrawn-7-abc@example.invalid");
        when(accountAnonymizationService.anonymizedNickname()).thenReturn("탈퇴abcdefghij");
        when(userMapper.finalizeWithdrawal(eq(USER_ID), any(), any(), any(), any(), any()))
                .thenReturn(1);
    }

    /** purgeOne 은 회원 한 명의 트랜잭션 경계여야 한다. */
    @Test
    void finalWithdrawalRevokesGalleryConsentAfterLockingTheMember() {
        service.purgeOne(USER_ID, NOW);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(userMapper, accountPurgeMapper);
        order.verify(userMapper).findPurgeTargetByIdForUpdate(USER_ID, NOW);
        order.verify(accountPurgeMapper).revokeReviewImageConsents(USER_ID);
        order.verify(userMapper).finalizeWithdrawal(eq(USER_ID), any(), any(), any(), any(), any());
    }

    /** purgeOne 은 회원 한 명의 트랜잭션 경계여야 한다. */
    @Test
    void purgeOneIsAPublicTransactionalBoundary() throws Exception {
        Method purgeOne = AccountPurgeTransactionService.class.getMethod(
                "purgeOne", Long.class, LocalDateTime.class);

        assertThat(purgeOne.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }

    @Test
    void theUserRowIsLockedAndRevalidatedBeforeAnythingIsTouched() {
        service.purgeOne(USER_ID, NOW);

        verify(userMapper).findPurgeTargetByIdForUpdate(USER_ID, NOW);
    }

    /** 복구가 먼저 ACTIVE 로 되돌렸으면 잠금 조회가 비고, 아무것도 하지 않는다. */
    @Test
    void anAccountRecoveredFirstMakesThePurgeANoOp() {
        when(userMapper.findPurgeTargetByIdForUpdate(USER_ID, NOW)).thenReturn(null);

        AccountPurgeTransactionService.PurgeOutcome outcome = service.purgeOne(USER_ID, NOW);

        assertThat(outcome.purged()).isFalse();
        verifyNoInteractions(accountPurgeMapper, accountAnonymizationService, diaryMapper,
                diaryCoverDesignMapper, accountRecoveryTokenMapper,
                googleTranslationDailyUsageMapper, socialAccountMapper);
        verify(userMapper, never()).finalizeWithdrawal(any(), any(), any(), any(), any(), any());
    }

    /** 관리자 계정은 조회 조건(user_role='USER')에서 이미 빠지므로 여기까지 오지 않는다. */
    @Test
    void aNonUserAccountIsNeverPurged() {
        when(userMapper.findPurgeTargetByIdForUpdate(USER_ID, NOW)).thenReturn(null);

        assertThat(service.purgeOne(USER_ID, NOW).purged()).isFalse();
        verify(userMapper, never()).finalizeWithdrawal(any(), any(), any(), any(), any(), any());
    }

    @Test
    void fileDeleteTasksAreRecordedBeforeTheRowsThatHoldTheUrlsAreDeleted() {
        when(userMapper.findPurgeTargetByIdForUpdate(USER_ID, NOW))
                .thenReturn(target("/uploads/profiles/p.jpg"));
        when(accountPurgeMapper.findDiaryImageUrlsByUserId(USER_ID)).thenReturn(List.of(
                "/uploads/diary-covers/c.jpg", "/uploads/diary-pages/e.jpg",
                // 다이어리에 적용된 표지 사진도 같은 조회에서 함께 온다
                "/uploads/diary-cover-elements/a.jpg"));
        when(accountPurgeMapper.findCoverDesignImageUrlsByUserId(USER_ID)).thenReturn(List.of(
                "/uploads/diary-cover-designs/d.jpg"));

        service.purgeOne(USER_ID, NOW);

        var order = org.mockito.Mockito.inOrder(accountPurgeMapper, diaryMapper,
                diaryCoverDesignMapper, userMapper);
        order.verify(accountPurgeMapper, times(5)).insertTask(any(AccountPurgeTask.class));
        order.verify(diaryMapper).deleteAllByUserId(USER_ID);
        order.verify(diaryCoverDesignMapper).deleteAllByUserId(USER_ID);
        order.verify(userMapper).finalizeWithdrawal(eq(USER_ID), any(), any(), any(), any(), any());

        assertThat(insertedTasks()).extracting(
                        AccountPurgeTask::getTaskType,
                        AccountPurgeTask::getTargetValue,
                        AccountPurgeTask::getStatus)
                .containsExactly(
                        tuple(AccountPurgeTaskType.FILE_DELETE, "/uploads/profiles/p.jpg",
                                AccountPurgeTaskStatus.PENDING),
                        tuple(AccountPurgeTaskType.FILE_DELETE, "/uploads/diary-covers/c.jpg",
                                AccountPurgeTaskStatus.PENDING),
                        tuple(AccountPurgeTaskType.FILE_DELETE, "/uploads/diary-pages/e.jpg",
                                AccountPurgeTaskStatus.PENDING),
                        tuple(AccountPurgeTaskType.FILE_DELETE,
                                "/uploads/diary-cover-elements/a.jpg",
                                AccountPurgeTaskStatus.PENDING),
                        tuple(AccountPurgeTaskType.FILE_DELETE, "/uploads/diary-cover-designs/d.jpg",
                                AccountPurgeTaskStatus.PENDING));
        assertThat(insertedTasks()).allSatisfy(task -> {
            assertThat(task.getPurgeJobId()).isEqualTo(100L);
            assertThat(task.getProvider()).isNull();
            assertThat(task.getAttempts()).isZero();
        });
    }

    @Test
    void staticAndUnexpectedFileReferencesDoNotBecomeTasks() {
        when(userMapper.findPurgeTargetByIdForUpdate(USER_ID, NOW)).thenReturn(target(null));
        when(accountPurgeMapper.findDiaryImageUrlsByUserId(USER_ID)).thenReturn(List.of(
                "/images/diary/stickers/travel/airplane.svg",
                "https://example.test/uploads/diary-pages/x.jpg",
                "/uploads/posts/public.jpg",
                "/uploads/diary-pages/../../etc/passwd",
                "/uploads/diary-pages/ok.jpg"));

        service.purgeOne(USER_ID, NOW);

        assertThat(insertedTasks()).extracting(AccountPurgeTask::getTargetValue)
                .containsExactly("/uploads/diary-pages/ok.jpg");
    }

    @Test
    void theSameFileReferencedTwiceProducesOneTask() {
        when(accountPurgeMapper.findDiaryImageUrlsByUserId(USER_ID)).thenReturn(List.of(
                "/uploads/diary-pages/same.jpg", "/uploads/diary-pages/same.jpg"));
        when(accountPurgeMapper.findCoverDesignImageUrlsByUserId(USER_ID)).thenReturn(List.of(
                "/uploads/diary-pages/same.jpg"));

        service.purgeOne(USER_ID, NOW);

        assertThat(insertedTasks()).hasSize(1);
    }

    @Test
    void onlyKakaoGetsASocialUnlinkTaskAndEverySocialRowIsDeleted() {
        when(socialAccountMapper.findAllByUserId(USER_ID)).thenReturn(List.of(
                socialAccount(SocialProvider.GOOGLE, "google-1"),
                socialAccount(SocialProvider.KAKAO, "kakao-1"),
                socialAccount(SocialProvider.NAVER, "naver-1")));

        service.purgeOne(USER_ID, NOW);

        assertThat(insertedTasks()).extracting(
                        AccountPurgeTask::getTaskType,
                        AccountPurgeTask::getProvider,
                        AccountPurgeTask::getTargetValue)
                .containsExactly(tuple(AccountPurgeTaskType.SOCIAL_UNLINK,
                        SocialProvider.KAKAO, "kakao-1"));
        // provider unlink 는 실행하지 않고 우리 DB 연결만 끊는다.
        verify(socialAccountMapper).deleteAllByUserId(USER_ID);
    }

    /**
     * 유예가 끝난 계정으로 같은 Kakao 로 다시 로그인해 즉시 파기하는 경우.
     * 그 Kakao 계정으로 곧바로 새로 가입할 참이라 unlink task 를 만들면 안 된다.
     * 나중에 worker 가 앱 연결을 끊어 새 계정의 연결까지 날아간다.
     */
    @Test
    void theProviderUsedToReAuthenticateDoesNotGetASocialUnlinkTask() {
        when(socialAccountMapper.findAllByUserId(USER_ID)).thenReturn(List.of(
                socialAccount(SocialProvider.KAKAO, "kakao-1")));

        AccountPurgeTransactionService.PurgeOutcome outcome = service.purgeOne(
                USER_ID, NOW, AccountPurgeOptions.reauthenticated(SocialProvider.KAKAO));

        assertThat(insertedTasks()).isEmpty();
        assertThat(outcome.jobStatus()).isEqualTo(AccountPurgeJobStatus.COMPLETED);
        // provider_user_id 점유는 풀어야 같은 계정으로 다시 가입할 수 있다.
        verify(socialAccountMapper).deleteAllByUserId(USER_ID);
    }

    /** 다른 provider 로 다시 로그인했다면 Kakao 연결은 원래 정책대로 끊어 준다. */
    @Test
    void aDifferentProviderReAuthenticationStillUnlinksKakao() {
        when(socialAccountMapper.findAllByUserId(USER_ID)).thenReturn(List.of(
                socialAccount(SocialProvider.GOOGLE, "google-1"),
                socialAccount(SocialProvider.KAKAO, "kakao-1")));

        service.purgeOne(USER_ID, NOW,
                AccountPurgeOptions.reauthenticated(SocialProvider.GOOGLE));

        assertThat(insertedTasks()).extracting(
                        AccountPurgeTask::getTaskType,
                        AccountPurgeTask::getProvider,
                        AccountPurgeTask::getTargetValue)
                .containsExactly(tuple(AccountPurgeTaskType.SOCIAL_UNLINK,
                        SocialProvider.KAKAO, "kakao-1"));
    }

    /** 자동 배치는 아무 provider 도 억제하지 않는다. 기존 정책 그대로다. */
    @Test
    void theScheduledPurgeStillCreatesTheKakaoUnlinkTask() {
        when(socialAccountMapper.findAllByUserId(USER_ID)).thenReturn(List.of(
                socialAccount(SocialProvider.KAKAO, "kakao-1")));

        service.purgeOne(USER_ID, NOW, AccountPurgeOptions.scheduled());

        assertThat(insertedTasks()).extracting(AccountPurgeTask::getProvider)
                .containsExactly(SocialProvider.KAKAO);
    }

    @Test
    void aBlankProviderUserIdDoesNotBecomeAnUnrunnableTask() {
        when(socialAccountMapper.findAllByUserId(USER_ID)).thenReturn(List.of(
                socialAccount(SocialProvider.KAKAO, "  ")));

        AccountPurgeTransactionService.PurgeOutcome outcome = service.purgeOne(USER_ID, NOW);

        assertThat(insertedTasks()).isEmpty();
        assertThat(outcome.jobStatus()).isEqualTo(AccountPurgeJobStatus.COMPLETED);
    }

    /**
     * 1:1 문의와 답변은 회원 탈퇴와 별개의 보존 수명(3년)을 갖는 업무기록이다.
     * 최종 파기가 즉시 지우면 안 되고, 정리는 문의 보존 배치만 한다.
     */
    @Test
    void inquiriesAreNotTouchedByTheAccountPurge() {
        service.purgeOne(USER_ID, NOW);

        assertThat(AccountPurgeTransactionService.class.getDeclaredFields())
                .extracting(field -> field.getType().getSimpleName())
                .doesNotContain("InquiryMapper", "InquiryRetentionTransactionService");
    }

    @Test
    void personalDataIsDeletedAndPublicContentIsLeftAlone() {
        service.purgeOne(USER_ID, NOW);

        verify(accountAnonymizationService).clearPersonalTraces(USER_ID);
        verify(diaryMapper).deleteAllByUserId(USER_ID);
        verify(diaryCoverDesignMapper).deleteAllByUserId(USER_ID);
        verify(accountRecoveryTokenMapper).deleteAllByUserId(USER_ID);
        verify(googleTranslationDailyUsageMapper).deleteAllByUserId(USER_ID);
        verify(socialAccountMapper).deleteAllByUserId(USER_ID);
    }

    @Test
    void theUserRowIsAnonymizedInPlaceWithTheSharedAnonymizationValues() {
        service.purgeOne(USER_ID, NOW);

        verify(accountAnonymizationService).anonymizedEmail(USER_ID);
        verify(accountAnonymizationService).anonymizedNickname();
        verify(userMapper).finalizeWithdrawal(
                USER_ID, "withdrawn-7-abc@example.invalid", "탈퇴abcdefghij",
                UserStatus.DEACTIVATED, NOW, NOW);
    }

    @Test
    void aJobWithPendingTasksStaysDbDone() {
        when(accountPurgeMapper.findDiaryImageUrlsByUserId(USER_ID))
                .thenReturn(List.of("/uploads/diary-pages/a.jpg"));

        AccountPurgeTransactionService.PurgeOutcome outcome = service.purgeOne(USER_ID, NOW);

        assertThat(outcome.jobStatus()).isEqualTo(AccountPurgeJobStatus.DB_DONE);
        assertThat(outcome.taskCount()).isEqualTo(1);
        verify(accountPurgeMapper).markJobDbDone(100L, NOW);
        verify(accountPurgeMapper, never()).markJobCompleted(anyLong(), any(), any());
    }

    @Test
    void aJobWithoutTasksIsCompletedRightAway() {
        AccountPurgeTransactionService.PurgeOutcome outcome = service.purgeOne(USER_ID, NOW);

        assertThat(outcome.jobStatus()).isEqualTo(AccountPurgeJobStatus.COMPLETED);
        assertThat(outcome.taskCount()).isZero();
        verify(accountPurgeMapper).markJobCompleted(100L, NOW, NOW);
        verify(accountPurgeMapper, never()).markJobDbDone(anyLong(), any());
    }

    @Test
    void theNewJobStartsAsPending() {
        service.purgeOne(USER_ID, NOW);

        ArgumentCaptor<AccountPurgeJob> captor = ArgumentCaptor.forClass(AccountPurgeJob.class);
        verify(accountPurgeMapper).insertJob(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getStatus()).isEqualTo(AccountPurgeJobStatus.PENDING);
    }

    /** user_id UNIQUE 라 재시도 상황에서 새 job 을 넣으면 duplicate key 로 끝난다. */
    @Test
    void anExistingJobIsReusedInsteadOfInsertingADuplicate() {
        AccountPurgeJob existing = new AccountPurgeJob();
        existing.setId(55L);
        existing.setUserId(USER_ID);
        existing.setStatus(AccountPurgeJobStatus.PENDING);
        when(accountPurgeMapper.findJobByUserIdForUpdate(USER_ID)).thenReturn(existing);
        when(accountPurgeMapper.findDiaryImageUrlsByUserId(USER_ID))
                .thenReturn(List.of("/uploads/diary-pages/a.jpg"));

        AccountPurgeTransactionService.PurgeOutcome outcome = service.purgeOne(USER_ID, NOW);

        verify(accountPurgeMapper, never()).insertJob(any(AccountPurgeJob.class));
        // 앞선 시도가 남긴 task 는 지우고 다시 모은다.
        verify(accountPurgeMapper).deleteTasksByJobId(55L);
        assertThat(outcome.purgeJobId()).isEqualTo(55L);
        assertThat(insertedTasks()).extracting(AccountPurgeTask::getPurgeJobId)
                .containsExactly(55L);
    }

    /** 조건부 UPDATE 가 0 행이면 그 사이 상태가 바뀐 것이므로 전부 되돌린다. */
    @Test
    void aFailedFinalizeAbortsTheWholeTransaction() {
        when(userMapper.finalizeWithdrawal(eq(USER_ID), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.purgeOne(USER_ID, NOW))
                .isInstanceOf(IllegalStateException.class);

        verify(accountPurgeMapper, never()).markJobDbDone(anyLong(), any());
        verify(accountPurgeMapper, never()).markJobCompleted(anyLong(), any(), any());
    }

    /** 실제 파일 삭제와 provider API 호출은 이번 단계에서 하지 않는다. */
    @Test
    void noFileOrProviderWorkIsPerformedYet() {
        when(userMapper.findPurgeTargetByIdForUpdate(USER_ID, NOW))
                .thenReturn(target("/uploads/profiles/p.jpg"));
        when(socialAccountMapper.findAllByUserId(USER_ID))
                .thenReturn(List.of(socialAccount(SocialProvider.KAKAO, "kakao-1")));

        service.purgeOne(USER_ID, NOW);

        assertThat(insertedTasks()).extracting(AccountPurgeTask::getStatus)
                .containsOnly(AccountPurgeTaskStatus.PENDING);
        assertThat(insertedTasks()).extracting(AccountPurgeTask::getCompletedAt)
                .containsOnlyNulls();
    }

    private List<AccountPurgeTask> insertedTasks() {
        ArgumentCaptor<AccountPurgeTask> captor = ArgumentCaptor.forClass(AccountPurgeTask.class);
        verify(accountPurgeMapper, org.mockito.Mockito.atLeast(0)).insertTask(captor.capture());
        return captor.getAllValues();
    }

    private User target(String profileImage) {
        User user = new User();
        user.setId(USER_ID);
        user.setUserRole(UserRole.USER);
        user.setStatus(UserStatus.WITHDRAWAL_PENDING);
        user.setProfileImage(profileImage);
        user.setPurgeScheduledAt(NOW.minusMinutes(1));
        return user;
    }

    private SocialAccount socialAccount(SocialProvider provider, String providerUserId) {
        SocialAccount account = new SocialAccount();
        account.setUserId(USER_ID);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        return account;
    }
}
