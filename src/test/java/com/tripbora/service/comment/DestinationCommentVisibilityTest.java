package com.tripbora.service.comment;

import com.tripbora.model.Destination;
import com.tripbora.model.DestinationComment;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.destination.DestinationNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DestinationCommentVisibilityTest {
    @Mock private DestinationMapper destinationMapper;
    @Mock private DestinationCommentMapper commentMapper;
    @InjectMocks private DestinationCommentService service;

    @Test
    void hiddenDestinationIsNotAccessibleToAnonymous() {
        givenDestination(false);
        assertThatThrownBy(() -> service.requireDestinationAccessible(10L, false))
                .isInstanceOf(DestinationNotFoundException.class);
        verifyNoInteractions(commentMapper);
    }

    @Test
    void hiddenDestinationIsNotAccessibleToOrdinaryUser() {
        givenDestination(false);
        assertThatThrownBy(() -> service.requireDestinationAccessible(10L, false))
                .isInstanceOf(DestinationNotFoundException.class);
        verifyNoInteractions(commentMapper);
    }

    @Test
    void administratorCanReadHiddenDestinationComments() {
        givenDestination(false);
        assertThatCode(() -> service.requireDestinationAccessible(10L, true)).doesNotThrowAnyException();
    }

    @Test
    void publicDestinationRemainsAccessibleToAnonymous() {
        givenDestination(true);
        assertThatCode(() -> service.requireDestinationAccessible(10L, false)).doesNotThrowAnyException();
    }

    @Test
    void missingDestinationIsNotAccessible() {
        assertThatThrownBy(() -> service.requireDestinationAccessible(10L, false))
                .isInstanceOf(DestinationNotFoundException.class);
    }

    @Test
    void translationChecksTheActualCommentDestination() {
        DestinationComment comment = new DestinationComment();
        comment.setDestinationId(10L);
        when(commentMapper.findById(35L)).thenReturn(comment);
        givenDestination(false);
        assertThatThrownBy(() -> service.requireCommentDestinationAccessible(35L, false))
                .isInstanceOf(DestinationNotFoundException.class);
    }

    private void givenDestination(boolean publicVisible) {
        Destination destination = new Destination();
        destination.setPublicVisible(publicVisible);
        when(destinationMapper.findById(10L)).thenReturn(destination);
    }

}
