package com.tripbora.service.course;

import com.tripbora.model.CourseDestination;
import com.tripbora.repository.category.CategoryMapper;
import com.tripbora.repository.category.CountryCategoryMapper;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.category.LocalizedReferenceNameResolver;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationLocalizationService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STOP 번호 다시 매기기.
 *
 * <p>여행지가 지워지면 그 연결 행도 FK CASCADE 로 함께 사라지는데, 남은 STOP 들은
 * 예전 번호를 그대로 들고 있다. 보고 있던 차례는 그대로 두고 번호만 1 부터 메꾼다.
 *
 * <p>여기서 고정하는 것은 재정렬 자체다.
 * 여행지 삭제와 이어 붙이는 것은 다음 단계다.
 */
@ExtendWith(MockitoExtension.class)
class CourseStopResequenceTest {

    private static final Long COURSE_ID = 10L;
    private static final Long OTHER_COURSE_ID = 11L;

    @Mock
    private DestinationImageService destinationImageService;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private CountryCategoryMapper countryCategoryMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private InfoCategoryMapper infoCategoryMapper;

    private CourseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CourseServiceImpl(courseMapper, new PostContentSanitizer(),
                new DestinationLocalizationService(destinationMapper),
                new ReferenceNameLocalizationService(countryCategoryMapper, categoryMapper,
                        infoCategoryMapper, new LocalizedReferenceNameResolver()),
                new LocalContentLanguageDetector(), destinationImageService,
                org.mockito.Mockito.mock(CourseStructureService.class));
    }

    @Test
    void theOnlyStopLeftBecomesTheFirstOne() {
        // 앞뒤가 모두 지워져 하나만 남았다. 화면에 "STOP 2" 하나가 남으면 안 된다
        givenStops(COURSE_ID, stop(11L, 2));
        givenUpdatesSucceed();

        service.resequenceStops(List.of(COURSE_ID));

        verify(courseMapper).updateCourseDestinationVisitOrder(11L, 1);
    }

    @Test
    void theGapsAreClosedWhileTheOrderStays() {
        givenStops(COURSE_ID, stop(11L, 2), stop(18L, 5), stop(23L, 9));
        givenUpdatesSucceed();

        service.resequenceStops(List.of(COURSE_ID));

        // 보고 있던 차례 그대로 1, 2, 3 이 된다
        InOrder order = inOrder(courseMapper);
        order.verify(courseMapper).updateCourseDestinationVisitOrder(11L, 1);
        order.verify(courseMapper).updateCourseDestinationVisitOrder(18L, 2);
        order.verify(courseMapper).updateCourseDestinationVisitOrder(23L, 3);
    }

    @Test
    void aCourseThatIsAlreadyTidyIsLeftAlone() {
        givenStops(COURSE_ID, stop(11L, 1), stop(18L, 2), stop(23L, 3));

        service.resequenceStops(List.of(COURSE_ID));

        // 번호가 이미 맞으면 굳이 다시 쓰지 않는다
        verify(courseMapper, never()).updateCourseDestinationVisitOrder(anyLong(), anyInt());
    }

    @Test
    void onlyTheStopsWhoseNumberActuallyMovedAreWritten() {
        // 앞의 둘은 그대로고 뒤의 하나만 밀린 자리다
        givenStops(COURSE_ID, stop(11L, 1), stop(18L, 2), stop(23L, 7));
        givenUpdatesSucceed();

        service.resequenceStops(List.of(COURSE_ID));

        verify(courseMapper).updateCourseDestinationVisitOrder(23L, 3);
        verify(courseMapper, never()).updateCourseDestinationVisitOrder(11L, 1);
        verify(courseMapper, never()).updateCourseDestinationVisitOrder(18L, 2);
    }

    @Test
    void stopsSharingOneNumberAreSplitByTheirRowNumber() {
        /*
          같은 visit_order 가 이미 들어 있는 어긋난 데이터.
          어느 쪽이 먼저인지는 조회가 id ASC 로 정하고(mapper 계약),
          여기서는 그렇게 온 차례를 그대로 따른다.
        */
        givenStops(COURSE_ID, stop(18L, 3), stop(23L, 3));
        givenUpdatesSucceed();

        service.resequenceStops(List.of(COURSE_ID));

        InOrder order = inOrder(courseMapper);
        order.verify(courseMapper).updateCourseDestinationVisitOrder(18L, 1);
        order.verify(courseMapper).updateCourseDestinationVisitOrder(23L, 2);
    }

    @Test
    void aCourseWithNothingLeftIsQuietlySkipped() {
        givenStops(COURSE_ID);

        service.resequenceStops(List.of(COURSE_ID));

        verify(courseMapper, never()).updateCourseDestinationVisitOrder(anyLong(), anyInt());
    }

    @Test
    void everyCourseIsTidiedOnItsOwn() {
        // 한 여행지가 여러 코스에 담겨 있었던 경우. 코스마다 1 부터 다시 센다
        givenStops(COURSE_ID, stop(11L, 4));
        givenStops(OTHER_COURSE_ID, stop(31L, 2), stop(37L, 6));
        givenUpdatesSucceed();

        service.resequenceStops(List.of(COURSE_ID, OTHER_COURSE_ID));

        verify(courseMapper).updateCourseDestinationVisitOrder(11L, 1);
        verify(courseMapper).updateCourseDestinationVisitOrder(31L, 1);
        verify(courseMapper).updateCourseDestinationVisitOrder(37L, 2);
    }

    @Test
    void theSameCourseTwiceIsStillOnePassOverIt() {
        givenStops(COURSE_ID, stop(11L, 2));
        givenUpdatesSucceed();

        service.resequenceStops(Arrays.asList(COURSE_ID, COURSE_ID, null));

        verify(courseMapper).findCourseStopOrders(COURSE_ID);
        verify(courseMapper).updateCourseDestinationVisitOrder(11L, 1);
    }

    @Test
    void nothingToTidyMeansNoQueryAtAll() {
        service.resequenceStops(null);
        service.resequenceStops(List.of());

        verify(courseMapper, never()).findCourseStopOrders(anyLong());
        verify(courseMapper, never()).updateCourseDestinationVisitOrder(anyLong(), anyInt());
    }

    @Test
    void aRowThatVanishedMidwayStopsTheWholeTidyUp() {
        /*
          방금 읽은 줄이 그 사이 사라졌다.
          절반만 옮긴 채로 두면 번호가 오히려 더 어긋난다. 여기서 끊어 전부 되돌린다.
        */
        givenStops(COURSE_ID, stop(11L, 2), stop(18L, 5));
        when(courseMapper.updateCourseDestinationVisitOrder(11L, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.resequenceStops(List.of(COURSE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("코스 여행지 순서 정리에 실패했습니다.");

        verify(courseMapper, never()).updateCourseDestinationVisitOrder(18L, 2);
    }

    @Test
    void theNumbersEndUpAsOneToNWithNoHolesLeft() {
        givenStops(COURSE_ID, stop(11L, 2), stop(18L, 5), stop(23L, 9), stop(40L, 12));

        List<Integer> written = new ArrayList<>();
        when(courseMapper.updateCourseDestinationVisitOrder(anyLong(), anyInt()))
                .thenAnswer(call -> {
                    written.add(call.getArgument(1));
                    return 1;
                });

        service.resequenceStops(List.of(COURSE_ID));

        // STOP 이 N 개면 번호는 언제나 1..N 이다
        assertThat(written).containsExactly(1, 2, 3, 4);
    }

    private void givenStops(Long courseId, CourseDestination... stops) {
        when(courseMapper.findCourseStopOrders(courseId)).thenReturn(List.of(stops));
    }

    /** 옮기는 데 성공한 것으로 둔다. 실패하는 경우는 따로 세운다. */
    private void givenUpdatesSucceed() {
        when(courseMapper.updateCourseDestinationVisitOrder(anyLong(), anyInt()))
                .thenReturn(1);
    }

    private CourseDestination stop(Long id, int visitOrder) {
        CourseDestination stop = new CourseDestination();
        stop.setId(id);
        stop.setVisitOrder(visitOrder);
        return stop;
    }
}
