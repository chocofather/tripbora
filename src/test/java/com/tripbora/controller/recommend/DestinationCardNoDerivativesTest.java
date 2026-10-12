package com.tripbora.controller.recommend;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.SeasonDestinationDto;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationViewClock;
import com.tripbora.service.file.DestinationCardThumbnailService;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.recommend.DestinationRecommendService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 공공누리 제3유형(변경금지) 대표 이미지는 카드에서도 줄이고 잘라 만든 썸네일 대신 원본을 쓴다.
 * 1유형은 기존처럼 카드 썸네일을 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class DestinationCardNoDerivativesTest {

    private static final String TYPE1 = "/uploads/destinations/type1.jpg";
    private static final String TYPE3 = "/uploads/destinations/type3.jpg";

    @TempDir Path uploadRoot;
    @Mock private DestinationRecommendService recommendService;
    @Mock private DestinationMapper destinationMapper;
    @Mock private FileUploadService fileUploadService;

    @Test
    void type3CardsKeepTheOriginalWhileType1CardsUseTheThumbnail() throws Exception {
        writeJpeg(uploadRoot.resolve("destinations/type1.jpg"));
        writeJpeg(uploadRoot.resolve("destinations/type3.jpg"));
        when(recommendService.findBySeason("SPRING", 5, SupportedLanguage.KOREAN))
                .thenReturn(List.of(destination(TYPE1), destination(TYPE3)));
        when(destinationMapper.findImageUrlsByLicenseType(any(), eq("KOGL_TYPE_3"))).thenReturn(List.of(TYPE3));
        DestinationImageService destinationImageService =
                new DestinationImageService(destinationMapper, fileUploadService);
        DestinationRecommendController controller = new DestinationRecommendController(recommendService,
                new DestinationCardThumbnailService(uploadRoot.toString(), destinationImageService),
                destinationImageService, new DestinationViewClock());

        List<SeasonDestinationDto> cards = controller.getSeasonDestinations("SPRING", null, 5, Locale.KOREAN);

        assertThat(cards.get(0).isImageNoDerivatives()).isFalse();
        assertThat(cards.get(0).getCardImageUrl()).isNotNull().isNotEqualTo(TYPE1);
        assertThat(cards.get(1).isImageNoDerivatives()).isTrue();
        assertThat(cards.get(1).getCardImageUrl()).isNull();
        assertThat(cards.get(1).getCardImageSrcset()).isNull();
        assertThat(cards.get(1).getImageUrl()).isEqualTo(TYPE3);
    }

    private SeasonDestinationDto destination(String imageUrl) {
        SeasonDestinationDto destination = new SeasonDestinationDto();
        destination.setImageUrl(imageUrl);
        return destination;
    }

    private void writeJpeg(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        ImageIO.write(new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB), "jpg", path.toFile());
    }
}
