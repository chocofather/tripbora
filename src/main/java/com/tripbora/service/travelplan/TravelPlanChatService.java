package com.tripbora.service.travelplan;

import com.tripbora.dto.TravelPlanChatEventDto;
import com.tripbora.dto.TravelPlanChatMessageDto;
import com.tripbora.dto.TravelPlanChatReactionDto;
import com.tripbora.dto.TravelPlanChatReactionRow;
import com.tripbora.dto.TravelPlanChatTimelineDto;
import com.tripbora.dto.TravelPlanChatTimelineItemDto;
import com.tripbora.model.TravelPlanChatMessage;
import com.tripbora.model.TravelPlanChatMessageType;
import com.tripbora.model.TravelPlanChatReactionType;
import com.tripbora.model.TravelPlanMember;
import com.tripbora.model.TravelPlanPoll;
import com.tripbora.repository.travelplan.TravelPlanChatMapper;
import com.tripbora.repository.travelplan.TravelPlanChatReactionMapper;
import com.tripbora.repository.travelplan.TravelPlanPollMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 방 채팅.
 *
 * <p>메시지는 메모리에 두지 않는다. DB 가 대화 기록의 기준이고,
 * 커밋이 끝난 뒤에만 다른 화면으로 나간다.
 *
 * <p>보낸 사람은 언제나 서버가 정한다. 클라이언트가 보낸 memberId / displayName 은 쓰지 않는다.
 * 이미 연결돼 있다고 해서 권한이 유지되지도 않는다. 쓰기 동작마다 ACTIVE 참여자인지 다시 본다.
 */
@Service
@RequiredArgsConstructor
public class TravelPlanChatService {

    /** 채팅 패널을 처음 열 때와 [이전 메시지 보기] 한 번에 가져오는 개수. */
    public static final int PAGE_SIZE = 40;

    /**
     * 한 메시지의 최대 길이.
     * content 는 TEXT 라 DB 상한이 훨씬 크지만, 화면과 전송량이 감당할 만한 선에서 끊는다.
     */
    public static final int MAX_CONTENT_LENGTH = 2000;

    private final TravelPlanChatMapper travelPlanChatMapper;
    /** 메시지에 달린 반응. 개수도 "내가 눌렀는지" 도 전부 여기서 센 값이다. */
    private final TravelPlanChatReactionMapper travelPlanChatReactionMapper;
    /** 채팅창에 끼워 넣을 "새 투표" 기록만 읽는다. 투표를 만들거나 고치지 않는다. */
    private final TravelPlanPollMapper travelPlanPollMapper;
    private final TravelPlanRoomAccess travelPlanRoomAccess;
    private final ApplicationEventPublisher eventPublisher;
    private final com.tripbora.repository.travelplan.TravelPlanChatImageMapper travelPlanChatImageMapper;

    /**
     * 채팅창에 그릴 한 페이지.
     *
     * <p>대화와 "새 투표를 만들었어요" 알림을 시간 순서로 합쳐 돌려준다.
     * 합치는 것은 여기 읽기 모델에서만이고, DB 에서는 두 표가 계속 따로다.
     * 투표를 채팅 행으로 옮겨 적거나 표시용 문자열을 남기지 않는다.
     *
     * <p>표가 둘이라 번호 하나로는 자를 수 없다.
     * 각자의 번호로 따로 끊어 와서 합치고, 다음 기준도 각자 돌려준다.
     * 대화 번호만 보고 자르면 그 사이의 투표 알림이 영영 빠진다.
     *
     * @param beforeMessageId 둘 다 null 이면 가장 최근 페이지
     * @param beforePollId    둘 다 null 이면 가장 최근 페이지
     */
    @Transactional(readOnly = true)
    public TravelPlanChatTimelineDto timeline(Principal principal, Long travelPlanId,
                                              Long beforeMessageId, Long beforePollId) {
        TravelPlanMember member = requireActiveMember(principal, travelPlanId);
        Timestamp visibleSince = visibleSince(member);
        boolean firstPage = beforeMessageId == null && beforePollId == null;

        List<TravelPlanChatMessage> messages = firstPage
                ? travelPlanChatMapper.findRecentMessages(travelPlanId, visibleSince, PAGE_SIZE)
                : beforeOrEmpty(beforeMessageId, before ->
                        travelPlanChatMapper.findMessagesBefore(
                                travelPlanId, before, visibleSince, PAGE_SIZE));
        List<TravelPlanPoll> polls = firstPage
                ? travelPlanPollMapper.findRecentPolls(travelPlanId, visibleSince, PAGE_SIZE)
                : beforeOrEmpty(beforePollId, before ->
                        travelPlanPollMapper.findPollsBefore(
                                travelPlanId, before, visibleSince, PAGE_SIZE));

        // 메시지마다 묻지 않는다. 이 쪽에 실린 것들의 반응을 한 번에 읽어 온다.
        Map<Long, List<TravelPlanChatReactionDto>> reactions =
                reactionsByMessageId(messages, member.getId());

        List<Long> imageMessageIds = messages.stream().filter(message -> message.getDeletedAt() == null)
                .map(TravelPlanChatMessage::getId).filter(java.util.Objects::nonNull).toList();
        if (!imageMessageIds.isEmpty()) {
            var imageRows = travelPlanChatImageMapper.findByMessageIds(imageMessageIds);
            Map<Long, List<com.tripbora.dto.TravelPlanChatImageDto>> grouped = new LinkedHashMap<>();
            for (var image : imageRows) grouped.computeIfAbsent(image.getMessageId(), ignored -> new ArrayList<>())
                    .add(com.tripbora.dto.TravelPlanChatImageDto.of(travelPlanId,image));
            messages.forEach(message -> message.setImages(grouped.getOrDefault(message.getId(),List.of())));
        }

        List<TravelPlanChatTimelineItemDto> items = new ArrayList<>();
        messages.stream()
                .map(message -> TravelPlanChatTimelineItemDto.ofMessage(
                        message, reactions.get(message.getId())))
                .forEach(items::add);
        polls.stream().map(TravelPlanChatTimelineItemDto::ofPollCreated).forEach(items::add);
        // 화면은 오래된 것이 위에 온다.
        items.sort(Comparator.comparing(
                TravelPlanChatTimelineItemDto::createdAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        // 각자 한 페이지를 꽉 채워 왔다면 그 앞에 더 있을 수 있다.
        boolean moreMessages = messages.size() >= PAGE_SIZE;
        boolean morePolls = polls.size() >= PAGE_SIZE;
        return new TravelPlanChatTimelineDto(
                items,
                moreMessages || morePolls,
                // 가져온 것이 없으면 그 쪽은 여기서 끝이다. 기준을 되돌려 같은 자리를 맴돌지 않는다.
                lastIdOf(messages, TravelPlanChatMessage::getId),
                lastIdOf(polls, TravelPlanPoll::getId));
    }

    /**
     * 메시지 저장.
     * 보낸 사람은 지금 로그인한 사람의 ACTIVE 참여 정보에서만 나온다.
     */
    @Transactional
    public TravelPlanChatMessageDto sendMessage(Principal principal, Long travelPlanId,
                                                String content) {
        TravelPlanMember member = requireActiveMember(principal, travelPlanId);
        String normalized = requireContent(content);

        TravelPlanChatMessage message = new TravelPlanChatMessage();
        message.setTravelPlanId(travelPlanId);
        message.setSenderMemberId(member.getId());
        message.setMessageType(TravelPlanChatMessageType.USER);
        message.setContent(normalized);

        if (travelPlanChatMapper.insertMessage(message) != 1) {
            throw new TravelPlanValidationException("content", "메시지를 보내지 못했습니다.");
        }
        // 이름은 방금 확인한 참여 정보 그대로다. 다시 읽어 오지 않는다.
        message.setSenderDisplayName(member.getDisplayName());

        TravelPlanChatMessageDto dto = TravelPlanChatMessageDto.of(message);
        // 보낸 사람에게도 이 알림으로 도착한다. 화면에 미리 그려 두지 않는다.
        eventPublisher.publishEvent(new TravelPlanChatChangedEvent(
                travelPlanId, TravelPlanChatEventDto.created(dto)));
        return dto;
    }

    /**
     * 본인이 보낸 메시지 지움.
     * 행을 지우지 않고 지움 표시만 남겨 대화 문맥이 당겨지지 않게 한다.
     *
     * <p>방장이라고 남의 메시지를 지울 수는 없다. 조건이 SQL 안에 있어 한 건도 바뀌지 않는다.
     */
    @Transactional
    public void deleteMessage(Principal principal, Long travelPlanId, Long messageId) {
        TravelPlanMember member = requireActiveMember(principal, travelPlanId);
        if (messageId == null) {
            throw new AccessDeniedException("지울 수 없는 메시지입니다.");
        }

        if (travelPlanChatMapper.markMessageDeleted(messageId, travelPlanId, member.getId()) != 1) {
            // 남의 메시지인지 이미 지워진 것인지 구분해 알리지 않는다.
            // 이미 지워져 있었다면 화면은 이미 지움으로 보이고 있어 더 할 일이 없다.
            TravelPlanChatMessage existing =
                    travelPlanChatMapper.findByIdAndPlanId(messageId, travelPlanId);
            if (existing != null && member.getId().equals(existing.getSenderMemberId())) {
                return;
            }
            throw new AccessDeniedException("내가 보낸 메시지만 지울 수 있습니다.");
        }

        eventPublisher.publishEvent(new TravelPlanChatChangedEvent(
                travelPlanId, TravelPlanChatEventDto.deleted(messageId)));
    }

    /**
     * 아직 읽지 않은 메시지 수. 내가 보낸 것과 지워진 것은 세지 않는다.
     * 볼 수 없는 대화도 세지 않는다. 목록과 같은 기준을 쓴다.
     */
    @Transactional(readOnly = true)
    public int unreadCount(Principal principal, Long travelPlanId) {
        TravelPlanMember member = requireActiveMember(principal, travelPlanId);
        return travelPlanChatMapper.countUnread(travelPlanId, member.getId(),
                visibleSince(member),
                travelPlanChatMapper.findLastReadMessageId(travelPlanId, member.getId()));
    }

    /**
     * 여기까지 읽었다고 표시한다.
     *
     * <p>읽음 위치가 실제로 앞으로 나아갈 때만 쓴다.
     * 스크롤할 때마다 같은 값을 다시 쓰지 않도록 이미 그 자리를 읽었으면 아무것도 하지 않는다.
     *
     * @param lastReadMessageId null 이면 이 방의 마지막 메시지까지 읽은 것으로 본다
     * @return 갱신 뒤의 안 읽은 개수
     */
    @Transactional
    public int markRead(Principal principal, Long travelPlanId, Long lastReadMessageId) {
        TravelPlanMember member = requireActiveMember(principal, travelPlanId);

        Long target = lastReadMessageId != null
                ? lastReadMessageId
                : travelPlanChatMapper.findLatestMessageId(travelPlanId);
        if (target == null) {
            // 아직 아무 대화도 없다.
            return 0;
        }

        Long current = travelPlanChatMapper.findLastReadMessageId(travelPlanId, member.getId());
        if (current == null || current < target) {
            travelPlanChatMapper.upsertReadPosition(travelPlanId, member.getId(), target);
            current = target;
        }
        return travelPlanChatMapper.countUnread(
                travelPlanId, member.getId(), visibleSince(member), current);
    }

    // ── 반응 ────────────────────────────────────────────────

    /**
     * 반응을 남기거나, 거두거나, 다른 종류로 바꾼다.
     *
     * <p>한 사람이 한 메시지에 남길 수 있는 반응은 하나뿐이다. 그래서 세 갈래다.
     * <ul>
     *   <li>아무 것도 없을 때 누르면 → 남긴다</li>
     *   <li>같은 것을 다시 누르면 → 거둔다</li>
     *   <li>다른 것을 누르면 → 더하지 않고 눌러 둔 것을 그 종류로 바꾼다</li>
     * </ul>
     *
     * <p>먼저 같은 종류를 지워 본다. 지워졌다면 "다시 누른 것" 이라 거기서 끝이고,
     * 아니면 남기거나 바꾼다. 지운 행 수가 곧 답이라 "있는지 물어보고 정하기" 와 달리
     * 두 번 누른 사이에 다른 요청이 끼어들 틈이 없다.
     * 남기고 바꾸는 것은 한 문장(upsert)이라 행이 없는 순간도 생기지 않고,
     * 한 사람의 행이 둘이 되지 않는 것은 (message_id, member_id) UNIQUE 가 막는다.
     *
     * <p>누가 눌렀는지는 언제나 서버가 정한다. 화면이 memberId 를 보내지 않는다.
     * 자기 메시지에도 반응할 수 있다.
     */
    @Transactional
    public void toggleReaction(Principal principal, Long travelPlanId,
                               Long messageId, String reactionType) {
        TravelPlanMember member = requireActiveMember(principal, travelPlanId);
        TravelPlanChatReactionType type = TravelPlanChatReactionType.from(reactionType);
        if (type == null) {
            throw new TravelPlanValidationException("reactionType", "지원하지 않는 반응입니다.");
        }
        if (messageId == null) {
            throw new AccessDeniedException("반응할 수 없는 메시지입니다.");
        }

        // 방 조건을 함께 걸어 읽는다. 다른 방 메시지 번호를 보내면 여기서 비어 온다.
        TravelPlanChatMessage message =
                travelPlanChatMapper.findByIdAndPlanId(messageId, travelPlanId);
        if (message == null || !canSee(member, message)) {
            throw new AccessDeniedException("반응할 수 없는 메시지입니다.");
        }
        if (message.getDeletedAt() != null) {
            throw new TravelPlanValidationException(
                    "messageId", "삭제된 메시지에는 반응할 수 없습니다.");
        }

        // 지워졌다면 같은 것을 다시 누른 것이라 거기서 끝이다.
        if (travelPlanChatReactionMapper.deleteReaction(
                messageId, member.getId(), type.name()) == 0) {
            // 아무 것도 없었으면 남기고, 다른 것을 눌러 두었으면 그 행을 이 종류로 바꾼다.
            travelPlanChatReactionMapper.upsertReaction(messageId, member.getId(), type.name());
        }

        eventPublisher.publishEvent(new TravelPlanChatChangedEvent(
                travelPlanId, TravelPlanChatEventDto.reactionChanged(messageId)));
    }

    /**
     * 한 메시지의 반응 요약.
     * 실시간 알림을 받은 화면이 개수를 더하는 대신 이 값을 다시 읽어 간다.
     * 그래서 같은 알림이 두 번 와도 결과가 같다.
     */
    @Transactional(readOnly = true)
    public List<TravelPlanChatReactionDto> reactionsOf(Principal principal, Long travelPlanId,
                                                       Long messageId) {
        TravelPlanMember member = requireActiveMember(principal, travelPlanId);
        if (messageId == null) {
            return List.of();
        }
        TravelPlanChatMessage message =
                travelPlanChatMapper.findByIdAndPlanId(messageId, travelPlanId);
        // 볼 수 없는 메시지의 반응도 알려 주지 않는다.
        if (message == null || !canSee(member, message) || message.getDeletedAt() != null) {
            return List.of();
        }
        return reactionsByMessageId(List.of(message), member.getId())
                .getOrDefault(messageId, List.of());
    }

    /**
     * 여러 메시지의 반응을 한 번에 읽어 메시지별로 묶는다.
     * 지워진 메시지는 아예 묻지 않는다(화면에도 내보내지 않는다).
     */
    private Map<Long, List<TravelPlanChatReactionDto>> reactionsByMessageId(
            List<TravelPlanChatMessage> messages, Long memberId) {
        List<Long> ids = messages.stream()
                .filter(message -> message.getDeletedAt() == null)
                .map(TravelPlanChatMessage::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        // IN () 은 SQL 오류다. 물을 것이 없으면 조회 자체를 보내지 않는다.
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<TravelPlanChatReactionDto>> grouped = new LinkedHashMap<>();
        for (TravelPlanChatReactionRow row
                : travelPlanChatReactionMapper.findSummaries(ids, memberId)) {
            TravelPlanChatReactionType type =
                    TravelPlanChatReactionType.from(row.getReactionType());
            // 아는 종류만 내보낸다. DB 에 남은 옛 값이 화면으로 새어 나가지 않는다.
            if (type == null || row.getCount() <= 0) {
                continue;
            }
            grouped.computeIfAbsent(row.getMessageId(), key -> new ArrayList<>())
                    .add(TravelPlanChatReactionDto.of(type, row.getCount(), row.isReacted()));
        }
        return grouped;
    }

    /** 그 사람이 볼 수 있는 대화인지. 들어오기 전의 메시지에는 반응도 할 수 없다. */
    private boolean canSee(TravelPlanMember member, TravelPlanChatMessage message) {
        Timestamp since = visibleSince(member);
        return since == null || message.getCreatedAt() == null
                || !message.getCreatedAt().before(since);
    }

    /**
     * 이 사람이 볼 수 있는 대화의 시작점.
     *
     * <p>그 방에 <em>참여한 시각</em>이다. 접속한 시각이 아니므로,
     * 참여한 뒤 한동안 꺼 두었다가 들어와도 그 사이 대화는 그대로 보인다.
     * 방을 만든 사람은 OWNER 행이 방과 함께 생기므로 처음부터 다 보인다.
     *
     * <p>joined_at 은 DB DEFAULT 로 채워지지만 만에 하나 비어 있으면
     * 같은 시각에 채워지는 created_at 을 대신 쓴다. 둘 다 없으면 자를 곳을
     * 알 수 없어 지금까지처럼 전부 보인다(대화를 통째로 막지는 않는다).
     */
    private Timestamp visibleSince(TravelPlanMember member) {
        return member.getJoinedAt() != null ? member.getJoinedAt() : member.getCreatedAt();
    }

    /** 기준이 없는 쪽은 이미 끝까지 본 것이다. 다시 읽지 않는다. */
    private <T> List<T> beforeOrEmpty(Long before, java.util.function.LongFunction<List<T>> read) {
        return before == null ? List.of() : read.apply(before);
    }

    /**
     * 이번에 읽어 온 것 중 가장 앞선 번호. 다음 페이지의 기준이 된다.
     * 최신순으로 오므로 마지막 것이 가장 오래된 것이다.
     */
    private <T> Long lastIdOf(List<T> newestFirst, java.util.function.Function<T, Long> idOf) {
        return newestFirst.isEmpty() ? null : idOf.apply(newestFirst.get(newestFirst.size() - 1));
    }

    /**
     * 보낼 수 있는 내용인지.
     * 줄바꿈은 그대로 두고 앞뒤 공백만 덜어 낸다. 공백만 보내는 것은 메시지로 보지 않는다.
     */
    private String requireContent(String content) {
        String normalized = content == null ? "" : content.strip();
        if (normalized.isEmpty()) {
            throw new TravelPlanValidationException("content", "메시지를 입력해 주세요.");
        }
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new TravelPlanValidationException("content",
                    "메시지는 " + MAX_CONTENT_LENGTH + "자까지 보낼 수 있습니다.");
        }
        return normalized;
    }

    /**
     * 지금 이 사람이 이 방에서 채팅할 수 있는지.
     * 끝난 방과 LEFT / REMOVED / 비참여자는 여기서 걸린다.
     */
    private TravelPlanMember requireActiveMember(Principal principal, Long travelPlanId) {
        return travelPlanRoomAccess.findActiveMember(principal, travelPlanId)
                .orElseThrow(() -> new AccessDeniedException("여행계획에 참여 중이 아닙니다."));
    }
}
