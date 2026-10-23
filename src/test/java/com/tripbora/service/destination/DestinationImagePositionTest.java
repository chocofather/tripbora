package com.tripbora.service.destination;

import com.tripbora.model.DestinationImage;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DestinationImagePositionTest {
    private final DestinationMapper mapper = mock(DestinationMapper.class);
    private final FileUploadService files = mock(FileUploadService.class);
    private final DestinationImageService service = new DestinationImageService(mapper, files);
    private DestinationImage image;

    @BeforeEach
    void image() {
        image = new DestinationImage();
        image.setId(8L);
        image.setDestinationId(3L);
        image.setIsMain(true);
    }

    @Test
    void savesOnlyCoordinatesForTheLinkedMainImageIncludingTheEdges() {
        when(mapper.findImageById(8L)).thenReturn(image);
        when(mapper.updateImageCardPosition(3L, 8L, 0, 100)).thenReturn(1);
        service.saveCardPosition(3L, 8L, 0, 100);
        verify(mapper).findImageById(8L);
        verify(mapper).updateImageCardPosition(3L, 8L, 0, 100);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(files);
    }

    @Test
    void invalidOrMissingCoordinatesNeverWrite() {
        for (Integer value : new Integer[]{null, -1, 101}) {
            assertThatThrownBy(() -> service.saveCardPosition(3L, 8L, value, 50))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.saveCardPosition(3L, 8L, 50, value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(mapper, files);
    }

    @Test
    void rejectsMissingForeignOrNoLongerMainImages() {
        when(mapper.findImageById(8L)).thenReturn(null, image, image);
        assertThatThrownBy(() -> service.saveCardPosition(3L, 8L, 50, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveCardPosition(99L, 8L, 50, 50))
                .isInstanceOf(IllegalArgumentException.class);
        image.setIsMain(false);
        assertThatThrownBy(() -> service.saveCardPosition(3L, 8L, 50, 50))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).updateImageCardPosition(any(), any(), anyInt(), anyInt());
        verifyNoInteractions(files);
    }

    @Test
    void aConcurrentDeleteOrMainChangeCannotSaveAgainstAStaleImage() {
        when(mapper.findImageById(8L)).thenReturn(image);
        when(mapper.updateImageCardPosition(3L, 8L, 20, 80)).thenReturn(0);
        assertThatThrownBy(() -> service.saveCardPosition(3L, 8L, 20, 80))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(files);
    }

    @Test
    void switchingMainFromAToBAndBackNeverOverwritesImagePositions() {
        DestinationImage second = new DestinationImage();
        second.setId(9L); second.setDestinationId(3L);
        image.setCardPositionX(20); image.setCardPositionY(80);
        second.setCardPositionX(75); second.setCardPositionY(30);
        when(mapper.findImageById(8L)).thenReturn(image);
        when(mapper.findImageById(9L)).thenReturn(second);
        service.setMainImage(3L, 9L);
        service.setMainImage(3L, 8L);
        verify(mapper).setMainImage(9L);
        verify(mapper).setMainImage(8L);
        verify(mapper, never()).updateImageCardPosition(any(), any(), anyInt(), anyInt());
        assertThat(image.getCardPositionX()).isEqualTo(20);
        assertThat(image.getCardPositionY()).isEqualTo(80);
        assertThat(second.getCardPositionX()).isEqualTo(75);
        assertThat(second.getCardPositionY()).isEqualTo(30);
        verifyNoInteractions(files);
    }
}
