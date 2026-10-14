package com.tripbora.service.file;

import com.tripbora.model.DiaryStickerType;
import com.tripbora.service.travelinfo.structured.StructuredImagePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class FileUploadService {

    private static final String DESTINATION_DIRECTORY = "destinations";
    private static final String DESTINATION_URL_PREFIX = "/uploads/destinations/";
    public static final String UNSUPPORTED_DESTINATION_IMAGE_MESSAGE =
            "JPEG 또는 PNG 이미지 파일만 업로드할 수 있습니다.";
    private static final String COMMENT_DIRECTORY = "comments";
    private static final String COMMENT_URL_PREFIX = "/uploads/comments/";
    private static final long TRAVEL_INFO_THUMBNAIL_MAX_SIZE = 5L * 1024 * 1024;

    /**
     * 여행정보 썸네일을 줄일 때 목표로 삼는 긴 변.
     *
     * <p>목록 카드는 약 278px 이라 훨씬 작아도 되지만, 같은 파일이 공유 카드의 og:image 로도
     * 쓰인다. 카카오톡·트위터 권장 폭이 1200px 이라 거기에 맞춘다.
     *
     * <p>모든 파일이 이 크기로 저장된다는 뜻은 아니다. 축소본을 채택했을 때의 긴 변일 뿐이고,
     * 언제 채택하는지는 {@link #saveTravelInfoThumbnail} 에 적어 두었다.
     */
    static final int TRAVEL_INFO_THUMBNAIL_MAX_EDGE = 1200;

    /**
     * 펼쳐서 메모리에 올리는 단계의 긴 변 상한.
     *
     * <p>최종 1200px 의 두 배라 단계적 축소가 2400 → 1200 한 걸음으로 끝난다.
     * 이 크기의 ARGB 한 장은 23MB 를 넘지 않아, 5MB 상한을 통과한 어떤 그림이 와도
     * 펼치는 데 드는 메모리가 여기에서 묶인다.
     */
    static final int TRAVEL_INFO_THUMBNAIL_DECODE_EDGE = 2400;

    private static final String TRAVEL_INFO_THUMBNAIL_DIRECTORY = "travel-info/thumbnails";
    private static final String TRAVEL_INFO_THUMBNAIL_URL_PREFIX =
            "/uploads/" + TRAVEL_INFO_THUMBNAIL_DIRECTORY + "/";
    /**
     * 구조화 콘텐츠(STRUCTURED) 본문 이미지의 긴 변 상한. 큰 이미지·슬라이더로 본문 폭 전체에 그려지므로
     * 썸네일보다 크게 남긴다. 이보다 크면 비율을 지켜 줄이고, 작으면 키우지 않는다.
     */
    static final int TRAVEL_INFO_CONTENT_IMAGE_MAX_EDGE = 2000;
    /** 펼치는 단계의 긴 변 상한. 썸네일과 같이 목표의 두 배라 한 걸음에 목표 크기가 된다. */
    static final int TRAVEL_INFO_CONTENT_IMAGE_DECODE_EDGE = 4000;
    /** 검증과 저장이 같은 도메인 policy를 사용한다. */
    static final String TRAVEL_INFO_CONTENT_IMAGE_URL_PREFIX =
            StructuredImagePolicy.TRAVEL_INFO.urlPrefix();
    /** 일반 saveFile/deleteSavedFile의 UUID 파일 검증도 기존 규칙을 유지한다. */
    private static final Pattern MANAGED_CONTENT_IMAGE_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png|webp)$");
    public static final String EMPTY_IMAGE_MESSAGE = "이미지를 선택해 주세요.";
    public static final String OVERSIZED_WEBP_CONTENT_IMAGE_MESSAGE =
            "WebP 이미지는 긴 변 2000px 이하만 올릴 수 있습니다. JPG·PNG 는 자동으로 줄여 저장합니다.";
    public static final String UNRESIZABLE_CONTENT_IMAGE_MESSAGE =
            "이미지를 줄이지 못했습니다. 긴 변 2000px 이하 이미지로 다시 올려 주세요.";
    private static final Pattern MANAGED_THUMBNAIL_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(?:jpg|png|webp)$",
            Pattern.CASE_INSENSITIVE);
    private static final String AMENITY_ICON_DIRECTORY = "icons/amenities";
    private static final String AMENITY_ICON_URL_PREFIX = "/uploads/" + AMENITY_ICON_DIRECTORY + "/";
    private static final long AMENITY_ICON_MAX_SIZE = 512L * 1024;
    private static final long DIARY_STICKER_MAX_SIZE = 5L * 1024 * 1024;
    private static final String DIARY_STICKER_DIRECTORY = "diary-stickers";
    private static final String DIARY_STICKER_URL_PREFIX = "/uploads/diary-stickers/";
    private static final Pattern MANAGED_DIARY_STICKER_PATH = Pattern.compile(
            "^(?:normal|masking-tape)/[0-9a-f-]+\\.(?:png|webp)$",
            Pattern.CASE_INSENSITIVE);

    /** 가져온 사진 한 장의 한도. Spring 의 max-file-size 와 같은 값이다. */
    private static final long DIARY_PHOTO_MAX_SIZE = 10L * 1024 * 1024;
    /**
     * 받아들일 사진 형식.
     * 이 목록은 서버가 실제로 펼쳐 볼 수 있는 것만 담는다. (WebP 는 표준 ImageIO 에 reader 가 없다)
     * 체험 화면의 고르개도 같은 목록을 쓴다. 한쪽만 넓으면 붙일 수는 있는데 가져올 수 없는 사진이 생긴다.
     */
    private static final java.util.Set<String> DIARY_PHOTO_FORMATS =
            java.util.Set.of("JPEG", "JPG", "PNG", "GIF");
    public static final String UNSUPPORTED_DIARY_PHOTO_MESSAGE =
            "JPG, PNG, GIF 사진만 저장할 수 있습니다.";
    public static final String OVERSIZED_DIARY_PHOTO_MESSAGE =
            "사진은 10MB까지 저장할 수 있습니다.";
    /** 일반 이미지 업로드 한도. multipart max-file-size 와 같은 값이다. */
    private static final long GENERAL_IMAGE_MAX_SIZE = 10L * 1024 * 1024;
    /** 검증 decode 때 한 변을 이 정도로 줄여 읽는다. (저장 파일은 원본 그대로다) */
    private static final int VALIDATION_DECODE_EDGE = 2048;
    public static final String UNSUPPORTED_IMAGE_MESSAGE =
            "JPG, PNG, WEBP 이미지 파일만 업로드할 수 있습니다.";
    public static final String OVERSIZED_IMAGE_MESSAGE =
            "이미지는 10MB까지 업로드할 수 있습니다.";
    /** 아이콘 파일명은 code 에서 만들어지므로 code 형식을 저장 직전에 한 번 더 확인한다. */
    private static final Pattern AMENITY_CODE = Pattern.compile("^[A-Z0-9_]{2,50}$");
    public static final String UNSUPPORTED_AMENITY_ICON_MESSAGE =
            "PNG, JPG 또는 SVG 이미지 파일만 업로드할 수 있습니다.";
    public static final String UNSAFE_AMENITY_SVG_MESSAGE =
            "안전하지 않은 SVG 파일입니다. 스크립트나 외부 참조가 없는 아이콘만 업로드할 수 있습니다.";
    /** 저장 파일명 후보. 같은 code 로 확장자만 다른 아이콘이 중복 생기지 않게 한다. */
    private static final List<String> AMENITY_ICON_EXTENSIONS =
            List.of("png", "jpg", "jpeg", "svg");
    private static final Set<String> FORBIDDEN_SVG_ELEMENTS = Set.of(
            "script", "iframe", "object", "embed", "foreignobject");
    /** 속성 값에서 금지하는 URI. 공백을 제거한 소문자 값으로 비교한다. */
    private static final List<String> FORBIDDEN_SVG_URI_SCHEMES =
            List.of("javascript:", "http://", "https://", "file:");

    /** 업로드한 파일을 가리키는 웹 경로의 앞머리. 이 밖의 경로는 다루지 않는다. */
    private static final String UPLOAD_URL_PREFIX = "/uploads/";

    private final String uploadDir;

    public FileUploadService(@Value("${custom.upload-path}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    /**
     * 기본 저장 - 상위 upload 폴더에 저장
     */
    public String saveFile(MultipartFile file) {
        return saveFile(file, "");
    }

    /**
     * 하위 디렉토리 포함 저장
     * @param file MultipartFile
     * @param subDir "events", "destinations" 등 하위 폴더 이름
     * @return 저장된 파일의 웹 URL 경로 (ex: /uploads/events/uuid.jpg)
     */
    public String saveFile(MultipartFile file, String subDir) {
        if (file == null || file.isEmpty()) return null;

        /*
          /uploads/** 는 누구나 열 수 있는 같은 origin 경로다.
          여기에 HTML/SVG 같은 파일이 저장되면 사이트 안에서 스크립트가 실행되므로,
          실제로 펼쳐지는 JPEG/PNG/WEBP 만 받는다.
          원본 파일명·확장자·클라이언트 MIME 은 믿지 않고, 확장자는 서버가 판별한 형식으로 정한다.
        */
        ImageFormat format = validateGeneralImage(file);
        return storeValidatedFile(file, subDir, format.extension);
    }

    /**
     * 검증을 마친 파일만 UUID 이름으로 저장한다.
     * 파일 이름에 사용자 입력을 쓰지 않고, 하위 폴더도 업로드 폴더 밖을 가리키지 못하게 한다.
     */
    private String storeValidatedFile(MultipartFile file, String subDir, String extension) {
        String directoryName = subDir == null ? "" : subDir;
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path directory = uploadRoot.resolve(directoryName).normalize();
        ensureContained(uploadRoot, directory);

        String savedName = UUID.randomUUID() + "." + extension;
        Path destination = directory.resolve(savedName).normalize();
        ensureContained(directory, destination);

        try (InputStream input = file.getInputStream()) {
            Files.createDirectories(directory);
            Files.copy(input, destination);
        } catch (IOException exception) {
            deleteAmenityIconPathQuietly(destination);
            // 서버 경로가 메시지로 새어 나가지 않게 원인만 붙여 둔다.
            throw new RuntimeException("파일 저장 실패", exception);
        }

        return directoryName.isEmpty()
                ? UPLOAD_URL_PREFIX + savedName
                : UPLOAD_URL_PREFIX + directoryName + "/" + savedName;
    }

    /**
     * {@link #saveFile(MultipartFile, String)} 가 만든 파일 하나를 지운다.
     *
     * <p>그 하위 폴더 바로 아래의 서버가 만든 이름(UUID + 판별한 확장자)만 다룬다.
     * 예전 방식의 경로나 다른 폴더를 가리키는 값은 건드리지 않고 {@code false} 를 돌려준다.
     */
    public boolean deleteSavedFile(String fileUrl, String subDir) {
        if (fileUrl == null || subDir == null || subDir.isBlank()) {
            return false;
        }
        String prefix = UPLOAD_URL_PREFIX + subDir + "/";
        if (!fileUrl.startsWith(prefix)) {
            return false;
        }
        String fileName = fileUrl.substring(prefix.length());
        if (!MANAGED_CONTENT_IMAGE_NAME.matcher(fileName).matches()) {
            return false;
        }
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path directory = uploadRoot.resolve(subDir).normalize();
        ensureContained(uploadRoot, directory);
        Path target = directory.resolve(fileName).normalize();
        ensureContained(directory, target);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new RuntimeException("파일 삭제 실패", exception);
        }
    }

    /**
     * 일반 이미지 업로드(에디터·게시글·댓글·다이어리·표지·이벤트)의 공통 검증.
     *
     * <p>파일 앞머리 signature 로 형식을 정하고, 그 형식으로 실제로 읽히는지까지 확인한다.
     * <ul>
     *   <li>JPEG/PNG: ImageIO 로 끝까지 decode 한다. 큰 사진이 메모리를 과하게 쓰지 않도록
     *       한 변이 {@value #VALIDATION_DECODE_EDGE}px 안쪽이 되게 건너뛰며 읽는다.</li>
     *   <li>WEBP: 표준 ImageIO 에 reader 가 없어 스티커 업로드와 같은 RIFF/VP8 구조 검증을 쓴다.</li>
     * </ul>
     * 크기는 multipart 한도와 같은 10MB 안쪽만 받으므로 WEBP 를 bytes 로 읽어도 상한이 있다.
     */
    private ImageFormat validateGeneralImage(MultipartFile file) {
        if (file.getSize() > GENERAL_IMAGE_MAX_SIZE) {
            throw new UnsupportedImageFormatException(OVERSIZED_IMAGE_MESSAGE);
        }

        ImageFormat format = detectGeneralImageFormat(file);
        if (format == ImageFormat.WEBP) {
            validateWebpStructure(file);
            return format;
        }

        try (InputStream input = file.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw unsupportedImage();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw unsupportedImage();
            }
            ImageReader reader = readers.next();
            try {
                if (!format.readerFormatName.equalsIgnoreCase(reader.getFormatName())) {
                    throw unsupportedImage();
                }
                reader.setInput(imageInput);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw unsupportedImage();
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, (int) Math.ceil(
                        (double) Math.max(width, height) / VALIDATION_DECODE_EDGE));
                param.setSourceSubsampling(step, step, 0, 0);
                // 머리말만 그럴듯한 파일을 막으려면 실제로 펼쳐 봐야 한다.
                reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof UnsupportedImageFormatException unsupported) {
                throw unsupported;
            }
            throw unsupportedImage();
        }
        return format;
    }

    /** 파일 앞머리만 보고 JPEG/PNG/WEBP 중 무엇인지 정한다. 셋 다 아니면 거부한다. */
    private ImageFormat detectGeneralImageFormat(MultipartFile file) {
        byte[] header = new byte[12];
        int length;
        try (InputStream input = file.getInputStream()) {
            length = input.readNBytes(header, 0, header.length);
        } catch (IOException exception) {
            throw unsupportedImage();
        }

        if (length >= 3
                && unsigned(header[0]) == 0xff
                && unsigned(header[1]) == 0xd8
                && unsigned(header[2]) == 0xff) {
            return ImageFormat.JPEG;
        }
        if (length >= 8
                && unsigned(header[0]) == 0x89
                && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && unsigned(header[4]) == 0x0d && unsigned(header[5]) == 0x0a
                && unsigned(header[6]) == 0x1a && unsigned(header[7]) == 0x0a) {
            return ImageFormat.PNG;
        }
        if (length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return ImageFormat.WEBP;
        }
        throw unsupportedImage();
    }

    /** WEBP 는 스티커 업로드와 같은 기준(RIFF 크기 일치 + 읽을 수 있는 VP8/VP8L 프레임)으로 본다. */
    private void validateWebpStructure(MultipartFile file) {
        try {
            byte[] data = file.getBytes();
            if (data.length < 20) {
                throw unsupportedImage();
            }
            long declaredSize = readLittleEndian(data, 4, 4);
            if (declaredSize + 8L != data.length
                    || !containsValidWebpFrame(data, 12, data.length)) {
                throw unsupportedImage();
            }
        } catch (IOException exception) {
            throw unsupportedImage();
        }
    }

    private UnsupportedImageFormatException unsupportedImage() {
        return new UnsupportedImageFormatException(UNSUPPORTED_IMAGE_MESSAGE);
    }

    /** 일반 이미지 업로드가 받는 형식. 저장 확장자는 여기서만 정한다. */
    private enum ImageFormat {
        JPEG("jpg", "jpeg"),
        PNG("png", "png"),
        WEBP("webp", "webp");

        private final String extension;
        private final String readerFormatName;

        ImageFormat(String extension, String readerFormatName) {
            this.extension = extension;
            this.readerFormatName = readerFormatName;
        }
    }

    /**
     * 일반 이미지 업로드 검증만 하고 저장은 하지 않는다.
     *
     * <p>개인 다이어리 사진은 공개 업로드 폴더가 아니라
     * {@link DiaryPrivatePhotoStorage} 가 저장한다. 저장 자리는 달라도 형식 검증은
     * 한 벌이어야 하므로, 그쪽이 이 자리를 빌려 쓴다.
     *
     * @return 서버가 파일 내용으로 정한 저장 확장자
     */
    public String validatedGeneralImageExtension(MultipartFile file) {
        return validateGeneralImage(file).extension;
    }

    /**
     * 체험 여행일기에서 가져온 사진의 검증만 하고 저장은 하지 않는다.
     *
     * <p>브라우저에서 이미 걸렀더라도 그것은 보안 경계가 아니다. 여기에서 다시 연다.
     * 파일 이름이나 클라이언트가 말한 MIME 을 믿지 않고 실제로 decode 되는지까지 본다.
     * (SVG 는 읽을 reader 가 없어 그대로 걸린다)
     *
     * @return 서버가 파일 내용으로 정한 저장 확장자. 체험 사진은 GIF 도 받는다.
     */
    public String validatedImportedDiaryPhotoExtension(MultipartFile file) {
        return validateImportedDiaryPhoto(file);
    }

    /**
     * 실제로 열리는 사진인지. 크기와 형식을 함께 본다.
     * @return 판별한 형식의 저장 확장자 (원본 파일명의 확장자는 쓰지 않는다)
     */
    private String validateImportedDiaryPhoto(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedImageFormatException("사진 파일을 선택해 주세요.");
        }
        if (file.getSize() > DIARY_PHOTO_MAX_SIZE) {
            throw new UnsupportedImageFormatException(OVERSIZED_DIARY_PHOTO_MESSAGE);
        }

        try (InputStream input = file.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw unsupportedDiaryPhoto();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw unsupportedDiaryPhoto();
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!DIARY_PHOTO_FORMATS.contains(format.toUpperCase(java.util.Locale.ROOT))) {
                    throw unsupportedDiaryPhoto();
                }
                reader.setInput(imageInput);
                if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                    throw unsupportedDiaryPhoto();
                }
                // 머리말만 그럴듯한 파일을 막으려면 실제로 펼쳐 봐야 한다.
                reader.read(0);
                return switch (format.toUpperCase(java.util.Locale.ROOT)) {
                    case "PNG" -> "png";
                    case "GIF" -> "gif";
                    default -> "jpg";
                };
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw unsupportedDiaryPhoto();
        }
    }

    private UnsupportedImageFormatException unsupportedDiaryPhoto() {
        return new UnsupportedImageFormatException(UNSUPPORTED_DIARY_PHOTO_MESSAGE);
    }

    /**
     * 관리자 여행지 직접 업로드 전용 저장.
     * 클라이언트가 보낸 파일명/Content-Type 은 신뢰하지 않고,
     * 실제 bytes 를 decode 해 JPEG/PNG 인 경우에만 저장한다.
     */
    public String saveDestinationImage(MultipartFile file) {
        DestinationImageFormat format = validateDestinationImage(file);
        Path destinationDirectory = resolveDestinationDirectory(true);
        String savedName = UUID.randomUUID() + "." + format.extension;
        Path destination = destinationDirectory.resolve(savedName).normalize();
        ensureContained(destinationDirectory, destination);

        try (InputStream input = file.getInputStream()) {
            Files.copy(input, destination);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
                // 원래 저장 실패를 우선 전달한다.
            }
            throw new RuntimeException("여행지 이미지 파일 저장에 실패했습니다.", exception);
        }
        return DESTINATION_URL_PREFIX + savedName;
    }

    public boolean deleteDestinationFile(String imageUrl) {
        String fileName = managedDestinationFileName(imageUrl);
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            if (Files.notExists(uploadRoot)) {
                return false;
            }
            Path realUploadRoot = uploadRoot.toRealPath();
            Path destinationDirectory = realUploadRoot.resolve(DESTINATION_DIRECTORY).normalize();
            ensureContained(realUploadRoot, destinationDirectory);
            if (Files.notExists(destinationDirectory)) {
                return false;
            }

            Path realDestinationDirectory = destinationDirectory.toRealPath();
            ensureContained(realUploadRoot, realDestinationDirectory);
            Path target = realDestinationDirectory.resolve(fileName).normalize();
            ensureContained(realDestinationDirectory, target);
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new RuntimeException("여행지 이미지 파일 삭제에 실패했습니다.", exception);
        }
    }

    public boolean deleteCommentFile(String imageUrl) {
        String fileName = managedCommentFileName(imageUrl);
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            if (Files.notExists(uploadRoot)) {
                return false;
            }
            Path realUploadRoot = uploadRoot.toRealPath();
            Path commentDirectory = realUploadRoot.resolve(COMMENT_DIRECTORY).normalize();
            ensureContained(realUploadRoot, commentDirectory);
            if (Files.notExists(commentDirectory)) {
                return false;
            }

            Path realCommentDirectory = commentDirectory.toRealPath();
            ensureContained(realUploadRoot, realCommentDirectory);
            Path target = realCommentDirectory.resolve(fileName).normalize();
            ensureContained(realCommentDirectory, target);
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new RuntimeException("댓글 이미지 파일 삭제에 실패했습니다.", exception);
        }
    }

    /**
     * 일반 여행정보(GENERAL)의 목록 썸네일을 저장한다.
     *
     * <p>이 파일은 목록 카드(약 278px)와 관리자 미리보기, 그리고 공유 카드의 og:image 로만 쓰인다.
     * 상세 본문에는 그려지지 않는다. 그래서 원본을 따로 남기지 않고 저장할 것 하나만 둔다.
     * 줄일 때의 목표를 {@value #TRAVEL_INFO_THUMBNAIL_MAX_EDGE}px 로 잡은 것은 목록 때문이 아니라
     * 공유 카드 권장 폭(1200px) 때문이다.
     *
     * <p>무엇을 저장하는지는 형식과 결과에 따라 갈린다. 목적이 내려받는 양을 줄이는 것이라
     * 줄인 쪽이 더 커지면 줄인 것을 버린다.
     * <ul>
     *   <li>긴 변 {@value #TRAVEL_INFO_THUMBNAIL_MAX_EDGE}px 이하 JPEG/PNG — 원본 바이트 그대로</li>
     *   <li>JPEG — 줄여서 다시 굽는다. 사진은 언제나 작아지므로 사실상 축소본이 저장된다</li>
     *   <li>PNG — 줄인 것이 원본보다 작을 때만 채택한다. 넓은 단색 면이 많은 PNG 는 줄이면
     *       보간 때문에 색이 잘게 번져 압축이 덜 되고, 오히려 파일이 커진다.
     *       그럴 때는 원본 크기 그대로 남는다</li>
     *   <li>WebP — 이 런타임의 ImageIO 가 읽지도 쓰지도 못한다. 원본 그대로</li>
     * </ul>
     *
     * <p>축제(FESTIVAL)의 목록 대표 이미지는 여기를 지나지 않는다. 그쪽은 파일을 따로 올리지 않고
     * 갤러리 사진 하나를 {@code is_thumbnail} 로 가리키는 방식이라, 같은 파일이 상세의 확대 보기에
     * 쓰인다. 줄이면 그 화면이 상한다.
     *
     * <p>줄이는 일은 파일을 만들기 전에 메모리에서 끝낸다. 그래야 줄이다가 잘못되어도
     * 반쯤 쓰다 만 파일이 남지 않는다.
     */
    public String saveTravelInfoThumbnail(MultipartFile file) {
        ThumbnailFormat format = validateTravelInfoThumbnail(file);

        byte[] content = readAllBytes(file);
        if (format.imageIoName != null && RasterImageResizer.canResize(format.imageIoName)) {
            byte[] resized = RasterImageResizer.optimize(content, format.imageIoName,
                    format.keepsAlpha,
                    TRAVEL_INFO_THUMBNAIL_MAX_EDGE, TRAVEL_INFO_THUMBNAIL_DECODE_EDGE);
            /*
              줄였는데 파일이 더 커지는 경우가 있다. 넓은 단색 면이 많은 PNG 를 줄이면
              보간 때문에 색이 잘게 번져서 압축이 덜 된다. 내려받는 양을 줄이려고 하는 일이라
              커진 쪽을 저장하면 앞뒤가 맞지 않는다. 그럴 때는 올라온 그대로 둔다.
             */
            if (resized.length < content.length) {
                content = resized;
            }
        }

        Path thumbnailDirectory = resolveThumbnailDirectory(true);
        String savedName = UUID.randomUUID() + "." + format.extension;
        Path destination = thumbnailDirectory.resolve(savedName).normalize();
        ensureContained(thumbnailDirectory, destination);

        try {
            Files.write(destination, content, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
                // 원래 저장 실패를 우선 전달한다.
            }
            throw new RuntimeException("썸네일 파일 저장에 실패했습니다.", exception);
        }
        return TRAVEL_INFO_THUMBNAIL_URL_PREFIX + savedName;
    }

    /** 크기 상한을 이미 확인한 뒤라 통째로 읽어도 된다. */
    private byte[] readAllBytes(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new RuntimeException("썸네일 파일 저장에 실패했습니다.", exception);
        }
    }

    public boolean deleteTravelInfoThumbnail(String imageUrl) {
        String fileName = managedThumbnailFileName(imageUrl);
        Path thumbnailDirectory = resolveThumbnailDirectory(false);
        if (thumbnailDirectory == null) {
            return false;
        }

        Path target = thumbnailDirectory.resolve(fileName).normalize();
        ensureContained(thumbnailDirectory, target);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new RuntimeException("썸네일 파일 삭제에 실패했습니다.", exception);
        }
    }

    /**
     * 구조화 콘텐츠(STRUCTURED) 본문 이미지 한 장을 저장하고, 저장한 그림의 실제 크기를 돌려준다.
     *
     * <p>검증은 일반 이미지 업로드와 같다. (앞머리 판별 + JPEG/PNG 실제 decode + WEBP 구조 검사, 10MB)
     * 이름·확장자·MIME 은 믿지 않고 저장 확장자도 판별한 형식으로 정한다. 형식을 바꿔 굽지 않는다.
     * <ul>
     *   <li>JPEG/PNG — 긴 변이 {@value #TRAVEL_INFO_CONTENT_IMAGE_MAX_EDGE}px 를 넘으면 비율을 지켜 줄인다.
     *       (EXIF 회전은 썸네일과 같이 픽셀에 반영된다) 그 안쪽이면 원본 바이트 그대로 둔다.</li>
     *   <li>WEBP — 이 런타임의 ImageIO 가 읽지도 쓰지도 못해 줄일 수 없다. 머리말에서 크기를 읽고,
     *       상한을 넘으면 저장하지 않고 거부한다.</li>
     * </ul>
     * 줄이기·저장 중에 실패하면 파일을 남기지 않는다. 그림은 메모리에서 끝낸 뒤 한 번에 쓴다.
     * 아직 어떤 여행정보와도 이어지지 않으므로 DB 에는 아무것도 쓰지 않는다.
     */
    public StoredContentImage saveTravelInfoContentImage(MultipartFile file) {
        return saveStructuredContentImage(file, StructuredImagePolicy.TRAVEL_INFO);
    }

    /** 도메인 endpoint가 서버에서 정한 policy로 호출한다. policy는 업로드 요청에서 받지 않는다. */
    public StoredContentImage saveStructuredContentImage(MultipartFile file, StructuredImagePolicy imagePolicy) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedImageFormatException(EMPTY_IMAGE_MESSAGE);
        }
        ImageFormat format = validateGeneralImage(file);
        byte[] content = readContentImageBytes(file);

        int[] size;
        if (format == ImageFormat.WEBP) {
            size = webpDimensions(content);
            if (size == null) {
                throw unsupportedImage();
            }
            if (Math.max(size[0], size[1]) > TRAVEL_INFO_CONTENT_IMAGE_MAX_EDGE) {
                throw new UnsupportedImageFormatException(OVERSIZED_WEBP_CONTENT_IMAGE_MESSAGE);
            }
        } else {
            content = RasterImageResizer.optimize(content, format.readerFormatName,
                    format == ImageFormat.PNG,
                    TRAVEL_INFO_CONTENT_IMAGE_MAX_EDGE, TRAVEL_INFO_CONTENT_IMAGE_DECODE_EDGE);
            size = uprightDimensions(content, format.readerFormatName);
            if (size == null) {
                throw unsupportedImage();
            }
            // 줄이기는 실패해도 원본을 돌려준다. 상한을 넘는 원본은 본문 이미지로 받지 않는다.
            if (Math.max(size[0], size[1]) > TRAVEL_INFO_CONTENT_IMAGE_MAX_EDGE) {
                throw new UnsupportedImageFormatException(UNRESIZABLE_CONTENT_IMAGE_MESSAGE);
            }
        }

        Path directory = resolveContainedDirectory(imagePolicy.directory(), true,
                "본문 이미지 저장 경로를 준비할 수 없습니다.");
        String savedName = UUID.randomUUID() + "." + format.extension;
        Path destination = directory.resolve(savedName).normalize();
        ensureContained(directory, destination);
        try {
            Files.write(destination, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
                // 원래 저장 실패를 우선 전달한다.
            }
            throw new RuntimeException("본문 이미지 저장에 실패했습니다.", exception);
        }
        return new StoredContentImage(imagePolicy.urlPrefix() + savedName, size[0], size[1]);
    }

    /**
     * 구조화 콘텐츠 본문 이미지 한 장을 지운다. 이미 없으면 false.
     *
     * <p>넘어온 문자열을 그대로 경로로 잇지 않는다. 전용 경로 아래 서버가 만든 이름(소문자 UUID + 확장자)인지
     * 다시 보고, 실제 경로가 전용 폴더 안인지 확인한 뒤에만 지운다. 그 밖의 값은 예외다.
     */
    public boolean deleteTravelInfoContentImage(String imageUrl) {
        return deleteStructuredContentImage(imageUrl, StructuredImagePolicy.TRAVEL_INFO);
    }

    public boolean deleteStructuredContentImage(String imageUrl, StructuredImagePolicy imagePolicy) {
        String fileName = imagePolicy.fileName(imageUrl);
        Path directory = resolveContainedDirectory(imagePolicy.directory(), false,
                "본문 이미지 저장 경로를 확인할 수 없습니다.");
        if (directory == null) {
            return false;
        }
        Path target = directory.resolve(fileName).normalize();
        ensureContained(directory, target);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new RuntimeException("본문 이미지 삭제에 실패했습니다.", exception);
        }
    }

    /** 저장한 본문 이미지. width / height 는 화면에 보이는(EXIF 회전을 반영한) 실제 크기다. */
    public record StoredContentImage(String url, int width, int height) {
    }

    /** 크기 상한(10MB)을 이미 확인한 뒤라 통째로 읽어도 된다. */
    private byte[] readContentImageBytes(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new RuntimeException("본문 이미지를 읽지 못했습니다.", exception);
        }
    }

    /** JPEG/PNG 의 화면 기준 크기. EXIF 가 90도 회전을 말하면(줄이지 않아 표시가 남은 경우) 가로·세로를 바꾼다. */
    private int[] uprightDimensions(byte[] content, String imageIoFormat) {
        try {
            int[] dimensions = RasterImageResizer.readDimensions(content, imageIoFormat);
            if (dimensions == null) {
                return null;
            }
            return JpegOrientation.swapsEdges(JpegOrientation.read(content))
                    ? new int[]{dimensions[1], dimensions[0]}
                    : dimensions;
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * 구조를 이미 검사한 WEBP 의 캔버스 크기. VP8X 가 있으면 그 캔버스, 없으면 첫 VP8 / VP8L 프레임 크기다.
     *
     * @return {@code {가로, 세로}}. 찾지 못하면 null
     */
    private int[] webpDimensions(byte[] data) {
        int offset = 12;
        while (offset + 8 <= data.length) {
            int chunkSize = (int) readLittleEndian(data, offset + 4, 4);
            int payload = offset + 8;
            if (payload + chunkSize > data.length) {
                return null;
            }
            if (matchesFourCc(data, offset, 'V', 'P', '8', 'X') && chunkSize >= 10) {
                return new int[]{(int) readLittleEndian(data, payload + 4, 3) + 1,
                        (int) readLittleEndian(data, payload + 7, 3) + 1};
            }
            if (matchesFourCc(data, offset, 'V', 'P', '8', ' ') && chunkSize >= 10) {
                return new int[]{(int) readLittleEndian(data, payload + 6, 2) & 0x3fff,
                        (int) readLittleEndian(data, payload + 8, 2) & 0x3fff};
            }
            if (matchesFourCc(data, offset, 'V', 'P', '8', 'L') && chunkSize >= 5) {
                int bits = (int) readLittleEndian(data, payload + 1, 4);
                return new int[]{(bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1};
            }
            offset = payload + chunkSize + (chunkSize & 1);
        }
        return null;
    }

    /** 관리자 스티커는 정적 리소스가 아닌 공용 업로드 저장소에 유형별로 보관한다. */
    public String saveDiaryStickerImage(MultipartFile file, DiaryStickerType type) {
        if (type == null) {
            throw new IllegalArgumentException("스티커 유형을 선택해 주세요.");
        }
        DiaryStickerImageFormat format = validateDiaryStickerImage(file);
        Path directory = resolveDiaryStickerDirectory(type.directoryName(), true);
        String savedName = UUID.randomUUID() + "." + format.extension;
        Path destination = directory.resolve(savedName).normalize();
        ensureContained(directory, destination);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, destination);
        } catch (IOException exception) {
            deleteAmenityIconPathQuietly(destination);
            throw new RuntimeException("스티커 이미지 저장에 실패했습니다.", exception);
        }
        return DIARY_STICKER_URL_PREFIX + type.directoryName() + "/" + savedName;
    }

    /** 등록 트랜잭션 실패 시 이번에 만든 파일만 되돌리는 용도다. 일반 숨김에서는 호출하지 않는다. */
    public boolean deleteDiaryStickerImage(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(DIARY_STICKER_URL_PREFIX)) {
            return false;
        }
        String relative = imageUrl.substring(DIARY_STICKER_URL_PREFIX.length());
        if (!MANAGED_DIARY_STICKER_PATH.matcher(relative).matches()) {
            return false;
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path target = root.resolve(DIARY_STICKER_DIRECTORY).resolve(relative).normalize();
        ensureContained(root.resolve(DIARY_STICKER_DIRECTORY).normalize(), target);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new RuntimeException("스티커 이미지 정리에 실패했습니다.", exception);
        }
    }

    private DiaryStickerImageFormat validateDiaryStickerImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("PNG 또는 WebP 이미지를 선택해 주세요.");
        }
        if (file.getSize() > DIARY_STICKER_MAX_SIZE) {
            throw new IllegalArgumentException("스티커 이미지는 5MB 이하만 업로드할 수 있습니다.");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()
                || originalName.contains("/") || originalName.contains("\\")
                || originalName.contains("..")) {
            throw new IllegalArgumentException("올바르지 않은 스티커 파일명입니다.");
        }
        int dot = originalName.lastIndexOf('.');
        if (dot <= 0 || dot == originalName.length() - 1) {
            throw new IllegalArgumentException("PNG 또는 WebP 이미지만 업로드할 수 있습니다.");
        }
        String extension = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        DiaryStickerImageFormat detected = detectDiaryStickerFormat(file);
        validateDiaryStickerPayload(file, detected);
        String contentType = file.getContentType();
        boolean matches = switch (detected) {
            case PNG -> "png".equals(extension) && "image/png".equalsIgnoreCase(contentType);
            case WEBP -> "webp".equals(extension) && "image/webp".equalsIgnoreCase(contentType);
        };
        if (!matches) {
            throw new IllegalArgumentException("파일 확장자, MIME 형식과 실제 이미지 형식이 일치하지 않습니다.");
        }
        return detected;
    }

    private void validateDiaryStickerPayload(MultipartFile file, DiaryStickerImageFormat format) {
        if (format == DiaryStickerImageFormat.PNG) {
            try (InputStream input = file.getInputStream()) {
                BufferedImage image = ImageIO.read(input);
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    throw invalidDiaryStickerImage();
                }
                return;
            } catch (IOException exception) {
                throw invalidDiaryStickerImage();
            }
        }

        try {
            byte[] data = file.getBytes();
            if (data.length < 20) throw invalidDiaryStickerImage();
            long declaredSize = readLittleEndian(data, 4, 4);
            if (declaredSize + 8L != data.length
                    || !containsValidWebpFrame(data, 12, data.length)) {
                throw invalidDiaryStickerImage();
            }
        } catch (IOException exception) {
            throw invalidDiaryStickerImage();
        }
    }

    private boolean containsValidWebpFrame(byte[] data, int start, int end) {
        return containsValidWebpFrame(data, start, end, true);
    }

    private boolean containsValidWebpFrame(byte[] data, int start, int end,
                                           boolean allowAnimationFrame) {
        int offset = start;
        boolean validFrame = false;
        while (offset < end) {
            if (end - offset < 8) return false;
            long chunkSizeValue = readLittleEndian(data, offset + 4, 4);
            if (chunkSizeValue > Integer.MAX_VALUE) return false;
            int chunkSize = (int) chunkSizeValue;
            int payloadStart = offset + 8;
            long chunkEndValue = (long) payloadStart + chunkSize;
            long paddedEndValue = chunkEndValue + (chunkSize & 1);
            if (chunkEndValue > end || paddedEndValue > end) return false;
            int chunkEnd = (int) chunkEndValue;

            if (matchesFourCc(data, offset, 'V', 'P', '8', ' ')) {
                validFrame |= isValidVp8Frame(data, payloadStart, chunkSize);
            } else if (matchesFourCc(data, offset, 'V', 'P', '8', 'L')) {
                validFrame |= isValidVp8lFrame(data, payloadStart, chunkSize);
            } else if (matchesFourCc(data, offset, 'V', 'P', '8', 'X')) {
                if (!isValidVp8xHeader(data, payloadStart, chunkSize)) return false;
            } else if (matchesFourCc(data, offset, 'A', 'N', 'M', 'F')) {
                if (!allowAnimationFrame || chunkSize < 16) return false;
                validFrame |= containsValidWebpFrame(
                        data, payloadStart + 16, chunkEnd, false);
            }
            offset = (int) paddedEndValue;
        }
        return offset == end && validFrame;
    }

    private boolean isValidVp8Frame(byte[] data, int start, int size) {
        if (size < 10) return false;
        if (unsigned(data[start + 3]) != 0x9d
                || unsigned(data[start + 4]) != 0x01
                || unsigned(data[start + 5]) != 0x2a) {
            return false;
        }
        int width = (int) readLittleEndian(data, start + 6, 2) & 0x3fff;
        int height = (int) readLittleEndian(data, start + 8, 2) & 0x3fff;
        return width > 0 && height > 0;
    }

    private boolean isValidVp8lFrame(byte[] data, int start, int size) {
        if (size < 5 || unsigned(data[start]) != 0x2f) return false;
        int dimensionsAndVersion = (int) readLittleEndian(data, start + 1, 4);
        int width = (dimensionsAndVersion & 0x3fff) + 1;
        int height = ((dimensionsAndVersion >> 14) & 0x3fff) + 1;
        int version = (dimensionsAndVersion >> 29) & 0x7;
        return width > 0 && height > 0 && version == 0;
    }

    private boolean isValidVp8xHeader(byte[] data, int start, int size) {
        if (size != 10) return false;
        int width = (int) readLittleEndian(data, start + 4, 3) + 1;
        int height = (int) readLittleEndian(data, start + 7, 3) + 1;
        return width > 0 && height > 0;
    }

    private boolean matchesFourCc(byte[] data, int offset, char a, char b, char c, char d) {
        return data[offset] == a && data[offset + 1] == b
                && data[offset + 2] == c && data[offset + 3] == d;
    }

    private long readLittleEndian(byte[] data, int offset, int length) {
        long value = 0;
        for (int index = 0; index < length; index++) {
            value |= (long) unsigned(data[offset + index]) << (8 * index);
        }
        return value;
    }

    private IllegalArgumentException invalidDiaryStickerImage() {
        return new IllegalArgumentException("실제 PNG 또는 WebP 이미지 파일만 업로드할 수 있습니다.");
    }

    private DiaryStickerImageFormat detectDiaryStickerFormat(MultipartFile file) {
        byte[] header = new byte[12];
        int length;
        try (InputStream input = file.getInputStream()) {
            length = input.read(header);
        } catch (IOException exception) {
            throw new RuntimeException("스티커 이미지를 확인할 수 없습니다.", exception);
        }
        if (length >= 8
                && unsigned(header[0]) == 0x89
                && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && unsigned(header[4]) == 0x0d && unsigned(header[5]) == 0x0a
                && unsigned(header[6]) == 0x1a && unsigned(header[7]) == 0x0a) {
            return DiaryStickerImageFormat.PNG;
        }
        if (length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return DiaryStickerImageFormat.WEBP;
        }
        throw new IllegalArgumentException("실제 PNG 또는 WebP 이미지 파일만 업로드할 수 있습니다.");
    }

    private Path resolveDiaryStickerDirectory(String typeDirectory, boolean create) {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path directory = root.resolve(DIARY_STICKER_DIRECTORY).resolve(typeDirectory).normalize();
        ensureContained(root, directory);
        try {
            if (create) Files.createDirectories(directory);
            if (!create && Files.notExists(directory)) return null;
            return directory;
        } catch (IOException exception) {
            throw new RuntimeException("스티커 저장 경로를 준비할 수 없습니다.", exception);
        }
    }

    private ThumbnailFormat validateTravelInfoThumbnail(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("썸네일 이미지를 선택해 주세요.");
        }
        if (file.getSize() > TRAVEL_INFO_THUMBNAIL_MAX_SIZE) {
            throw new IllegalArgumentException("썸네일 이미지는 5MB 이하만 업로드할 수 있습니다.");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()
                || originalName.contains("/") || originalName.contains("\\")
                || originalName.contains("..")) {
            throw new IllegalArgumentException("올바르지 않은 썸네일 파일명입니다.");
        }

        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == originalName.length() - 1) {
            throw new IllegalArgumentException("JPG, PNG, WebP 이미지만 업로드할 수 있습니다.");
        }
        String requestedExtension = originalName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        String contentType = file.getContentType();
        ThumbnailFormat detected = detectThumbnailFormat(file);

        boolean matches = switch (detected) {
            case JPEG -> ("jpg".equals(requestedExtension) || "jpeg".equals(requestedExtension))
                    && "image/jpeg".equalsIgnoreCase(contentType);
            case PNG -> "png".equals(requestedExtension)
                    && "image/png".equalsIgnoreCase(contentType);
            case WEBP -> "webp".equals(requestedExtension)
                    && "image/webp".equalsIgnoreCase(contentType);
        };
        if (!matches) {
            throw new IllegalArgumentException("파일 확장자, MIME 형식과 실제 이미지 형식이 일치하지 않습니다.");
        }
        return detected;
    }

    private ThumbnailFormat detectThumbnailFormat(MultipartFile file) {
        byte[] header = new byte[12];
        int length;
        try (InputStream input = file.getInputStream()) {
            length = input.read(header);
        } catch (IOException exception) {
            throw new RuntimeException("썸네일 파일을 확인할 수 없습니다.", exception);
        }

        if (length >= 3
                && unsigned(header[0]) == 0xff
                && unsigned(header[1]) == 0xd8
                && unsigned(header[2]) == 0xff) {
            return ThumbnailFormat.JPEG;
        }
        if (length >= 8
                && unsigned(header[0]) == 0x89
                && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && unsigned(header[4]) == 0x0d && unsigned(header[5]) == 0x0a
                && unsigned(header[6]) == 0x1a && unsigned(header[7]) == 0x0a) {
            return ThumbnailFormat.PNG;
        }
        if (length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return ThumbnailFormat.WEBP;
        }
        throw new IllegalArgumentException("실제 JPG, PNG 또는 WebP 이미지 파일만 업로드할 수 있습니다.");
    }

    private Path resolveThumbnailDirectory(boolean create) {
        return resolveContainedDirectory(TRAVEL_INFO_THUMBNAIL_DIRECTORY, create,
                "썸네일 저장 경로를 준비할 수 없습니다.");
    }

    /**
     * 업로드 루트 아래 정해진 하위 폴더의 실제 경로. 링크 등으로 루트 밖을 가리키면 거부한다.
     *
     * @param create false 면 폴더가 없을 때 만들지 않고 null 을 돌려준다
     */
    private Path resolveContainedDirectory(String directoryName, boolean create, String failureMessage) {
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            if (create) {
                Files.createDirectories(uploadRoot);
            } else if (Files.notExists(uploadRoot)) {
                return null;
            }

            Path realUploadRoot = uploadRoot.toRealPath();
            Path directory = realUploadRoot.resolve(directoryName).normalize();
            ensureContained(realUploadRoot, directory);
            if (create) {
                Files.createDirectories(directory);
            } else if (Files.notExists(directory)) {
                return null;
            }

            Path realDirectory = directory.toRealPath();
            ensureContained(realUploadRoot, realDirectory);
            return realDirectory;
        } catch (IOException exception) {
            throw new RuntimeException(failureMessage, exception);
        }
    }

    private String managedThumbnailFileName(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(TRAVEL_INFO_THUMBNAIL_URL_PREFIX)) {
            throw new IllegalArgumentException("관리 대상이 아닌 썸네일 경로입니다.");
        }
        String fileName = imageUrl.substring(TRAVEL_INFO_THUMBNAIL_URL_PREFIX.length());
        if (!MANAGED_THUMBNAIL_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("올바르지 않은 썸네일 경로입니다.");
        }
        return fileName;
    }

    private String managedDestinationFileName(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(DESTINATION_URL_PREFIX)) {
            throw new IllegalArgumentException("관리 대상이 아닌 여행지 이미지 경로입니다.");
        }
        String fileName = imageUrl.substring(DESTINATION_URL_PREFIX.length());
        if (fileName.isBlank()
                || fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains("..")) {
            throw new IllegalArgumentException("올바르지 않은 여행지 이미지 경로입니다.");
        }
        return fileName;
    }

    private DestinationImageFormat validateDestinationImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedImageFormatException("이미지 파일을 선택해 주세요.");
        }

        try (InputStream input = file.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw unsupportedDestinationImage();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw unsupportedDestinationImage();
            }

            ImageReader reader = readers.next();
            try {
                DestinationImageFormat format = DestinationImageFormat.of(reader.getFormatName());
                reader.setInput(imageInput);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw unsupportedDestinationImage();
                }
                /*
                  실제로 decode 되는 이미지인지까지 확인한다. 여행지 사진은 한 장에 20MB(수천만 화소)까지 받으므로
                  일반 이미지 검증처럼 건너뛰며 펼쳐 메모리를 제한한다. 끝까지 읽는 것은 같아 깨진 파일은 똑같이 걸러진다.
                  저장되는 원본 바이트는 건드리지 않는다.
                */
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, (int) Math.ceil(
                        (double) Math.max(width, height) / VALIDATION_DECODE_EDGE));
                param.setSourceSubsampling(step, step, 0, 0);
                reader.read(0, param);
                return format;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw unsupportedDestinationImage();
        }
    }

    private UnsupportedImageFormatException unsupportedDestinationImage() {
        return new UnsupportedImageFormatException(UNSUPPORTED_DESTINATION_IMAGE_MESSAGE);
    }

    private Path resolveDestinationDirectory(boolean create) {
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            if (create) {
                Files.createDirectories(uploadRoot);
            } else if (Files.notExists(uploadRoot)) {
                return null;
            }

            Path realUploadRoot = uploadRoot.toRealPath();
            Path destinationDirectory = realUploadRoot.resolve(DESTINATION_DIRECTORY).normalize();
            ensureContained(realUploadRoot, destinationDirectory);
            if (create) {
                Files.createDirectories(destinationDirectory);
            } else if (Files.notExists(destinationDirectory)) {
                return null;
            }

            Path realDestinationDirectory = destinationDirectory.toRealPath();
            ensureContained(realUploadRoot, realDestinationDirectory);
            return realDestinationDirectory;
        } catch (IOException exception) {
            throw new RuntimeException("여행지 이미지 저장 경로를 준비할 수 없습니다.", exception);
        }
    }

    private String managedCommentFileName(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(COMMENT_URL_PREFIX)) {
            throw new IllegalArgumentException("관리 대상이 아닌 댓글 이미지 경로입니다.");
        }
        String fileName = imageUrl.substring(COMMENT_URL_PREFIX.length());
        if (fileName.isBlank()
                || fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains("..")) {
            throw new IllegalArgumentException("올바르지 않은 댓글 이미지 경로입니다.");
        }
        return fileName;
    }

    /**
     * 편의시설 아이콘 파일만 검증한다. 저장은 하지 않으므로 DB INSERT 전에 먼저 부를 수 있다.
     * 클라이언트가 보낸 확장자/Content-Type 을 믿지 않고 실제 bytes 까지 확인한다.
     */
    public void validateAmenityIcon(MultipartFile file) {
        verifyAmenityIcon(file);
    }

    /**
     * 업로드된 아이콘을 검증하고 저장에 쓸 확장자를 돌려준다. 포맷 변환은 하지 않는다.
     * 확장자, Content-Type, 실제 내용이 모두 같은 포맷을 가리킬 때만 통과한다.
     *
     * @return 저장 확장자 ("png" / "jpg" / "jpeg" / "svg")
     */
    private String verifyAmenityIcon(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("아이콘 이미지를 선택해 주세요.");
        }
        if (file.getSize() > AMENITY_ICON_MAX_SIZE) {
            throw new IllegalArgumentException("아이콘 이미지는 512KB 이하만 업로드할 수 있습니다.");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()
                || originalName.contains("/") || originalName.contains("\\")
                || originalName.contains("..")) {
            throw new IllegalArgumentException("올바르지 않은 아이콘 파일명입니다.");
        }

        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == originalName.length() - 1) {
            throw unsupportedAmenityIcon();
        }
        String requestedExtension = originalName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        String contentType = file.getContentType();

        // SVG 는 이미지가 아니라 XML 이므로 ImageIO 가 아닌 별도 경로로 검사한다.
        if ("svg".equals(requestedExtension)) {
            if (!"image/svg+xml".equalsIgnoreCase(contentType)) {
                throw unsupportedAmenityIcon();
            }
            validateSvgIcon(file);
            return "svg";
        }

        AmenityIconFormat detected = detectAmenityIconFormat(file);
        // 확장자만 바꾼 위장 파일을 막기 위해 세 값이 모두 같은 포맷을 가리켜야 한다.
        boolean matches = switch (detected) {
            case PNG -> "png".equals(requestedExtension)
                    && "image/png".equalsIgnoreCase(contentType);
            case JPEG -> ("jpg".equals(requestedExtension) || "jpeg".equals(requestedExtension))
                    && "image/jpeg".equalsIgnoreCase(contentType);
        };
        if (!matches) {
            throw unsupportedAmenityIcon();
        }
        decodeAmenityIcon(file, detected);
        // JPG 와 JPEG 는 올린 그대로의 확장자를 유지한다.
        return requestedExtension;
    }

    private AmenityIconFormat detectAmenityIconFormat(MultipartFile file) {
        byte[] header = new byte[8];
        int length;
        try (InputStream input = file.getInputStream()) {
            length = input.read(header);
        } catch (IOException exception) {
            throw unsupportedAmenityIcon();
        }

        if (length >= 8
                && unsigned(header[0]) == 0x89
                && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && unsigned(header[4]) == 0x0d && unsigned(header[5]) == 0x0a
                && unsigned(header[6]) == 0x1a && unsigned(header[7]) == 0x0a) {
            return AmenityIconFormat.PNG;
        }
        if (length >= 3
                && unsigned(header[0]) == 0xff
                && unsigned(header[1]) == 0xd8
                && unsigned(header[2]) == 0xff) {
            return AmenityIconFormat.JPEG;
        }
        throw unsupportedAmenityIcon();
    }

    private UnsupportedImageFormatException unsupportedAmenityIcon() {
        return new UnsupportedImageFormatException(UNSUPPORTED_AMENITY_ICON_MESSAGE);
    }

    private UnsupportedImageFormatException unsafeSvgIcon() {
        return new UnsupportedImageFormatException(UNSAFE_AMENITY_SVG_MESSAGE);
    }

    /**
     * SVG 아이콘 검사. 파일로 저장해 &lt;img&gt; 로만 렌더링하지만, 업로드 자체를 보수적으로 막는다.
     * DTD/외부 엔티티(XXE)를 끄고 파싱한 뒤 위험한 요소·속성·URI 를 전부 거부한다.
     */
    private void validateSvgIcon(MultipartFile file) {
        Document document;
        try (InputStream input = file.getInputStream()) {
            document = secureSvgDocumentBuilder().parse(input);
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            // DOCTYPE 선언도 여기서 걸린다(disallow-doctype-decl).
            throw unsafeSvgIcon();
        }

        Element root = document.getDocumentElement();
        if (root == null || !"svg".equalsIgnoreCase(localName(root))) {
            throw unsupportedAmenityIcon();
        }
        checkSvgElement(root);
    }

    private DocumentBuilder secureSvgDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("external entity is not allowed");
        });
        return builder;
    }

    private void checkSvgElement(Element element) {
        if (FORBIDDEN_SVG_ELEMENTS.contains(localName(element).toLowerCase(Locale.ROOT))) {
            throw unsafeSvgIcon();
        }

        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName();
            // 네임스페이스 선언은 정상적으로 http://www.w3.org/... 를 가리키므로 제외한다.
            if ("xmlns".equals(name) || name.startsWith("xmlns:")
                    || XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                continue;
            }
            if (localName(attribute).toLowerCase(Locale.ROOT).startsWith("on")) {
                throw unsafeSvgIcon();
            }
            String value = attribute.getNodeValue() == null
                    ? "" : attribute.getNodeValue().toLowerCase(Locale.ROOT).replace(" ", "");
            for (String forbidden : FORBIDDEN_SVG_URI_SCHEMES) {
                if (value.contains(forbidden)) {
                    throw unsafeSvgIcon();
                }
            }
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                checkSvgElement(childElement);
            }
        }
    }

    private String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    /**
     * 신규 편의시설 아이콘 저장.
     * 업로드한 포맷을 변환하지 않고 원본 bytes 를 그대로 저장하며,
     * 파일명만 {code 소문자}.{실제확장자} 로 고정한다.
     *
     * @return 웹 URL (예: /uploads/icons/amenities/free_wifi.svg)
     */
    public String saveAmenityIcon(String code, MultipartFile file) {
        String extension = verifyAmenityIcon(file);
        String savedName = amenityIconFileName(code, extension);
        Path iconDirectory = resolveAmenityIconDirectory(true);
        Path destination = iconDirectory.resolve(savedName).normalize();
        ensureContained(iconDirectory, destination);
        // 확장자가 달라도 같은 code 의 아이콘이 이미 있으면 신규 등록으로 보지 않는다.
        ensureNoExistingAmenityIcon(iconDirectory, code);

        boolean created = false;
        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(
                     destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;
            input.transferTo(output);
        } catch (FileAlreadyExistsException exception) {
            throw amenityIconAlreadyExists(exception);
        } catch (IOException exception) {
            if (created) {
                deleteAmenityIconPathQuietly(destination);
            }
            throw new IllegalStateException("편의시설 아이콘 파일 저장에 실패했습니다.", exception);
        }
        return AMENITY_ICON_URL_PREFIX + savedName;
    }

    /**
     * 기존 편의시설 아이콘 교체 준비.
     * 임시 파일에 먼저 쓰고, 같은 이름의 기존 파일은 백업해 둔 뒤 원자적으로 바꿔치기한다.
     * 호출자는 트랜잭션 결과에 따라 {@link #commitAmenityIconReplacement}
     * 또는 {@link #rollbackAmenityIconReplacement} 를 반드시 불러야 한다.
     */
    public AmenityIconReplacement replaceAmenityIcon(String code, MultipartFile file) {
        String extension = verifyAmenityIcon(file);
        String savedName = amenityIconFileName(code, extension);
        Path iconDirectory = resolveAmenityIconDirectory(true);
        Path destination = iconDirectory.resolve(savedName).normalize();
        ensureContained(iconDirectory, destination);

        // 1) 새 내용을 임시 파일에 먼저 기록한다. 여기서 실패해도 기존 아이콘은 그대로다.
        Path temporary = iconDirectory.resolve(savedName + ".tmp-" + UUID.randomUUID()).normalize();
        ensureContained(iconDirectory, temporary);
        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(
                     temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            input.transferTo(output);
        } catch (IOException exception) {
            deleteAmenityIconPathQuietly(temporary);
            throw new IllegalStateException("편의시설 아이콘 파일 저장에 실패했습니다.", exception);
        }

        // 2) 같은 이름의 기존 파일은 지우지 않고 백업해 둔다(롤백 시 되돌린다).
        Path backup = null;
        try {
            if (Files.exists(destination)) {
                backup = iconDirectory.resolve(savedName + ".bak-" + UUID.randomUUID()).normalize();
                ensureContained(iconDirectory, backup);
                Files.move(destination, backup, StandardCopyOption.ATOMIC_MOVE);
            }
            // 3) 임시 파일을 최종 이름으로 옮긴다.
            moveIntoPlace(temporary, destination);
        } catch (IOException exception) {
            deleteAmenityIconPathQuietly(temporary);
            restoreAmenityIconBackup(backup, destination);
            throw new IllegalStateException("편의시설 아이콘 파일 교체에 실패했습니다.", exception);
        }
        return new AmenityIconReplacement(
                AMENITY_ICON_URL_PREFIX + savedName, code, extension, destination, backup);
    }

    /** 교체 확정. 백업과 이전 확장자로 남아 있던 아이콘을 정리한다. */
    public void commitAmenityIconReplacement(AmenityIconReplacement replacement) {
        if (replacement == null) {
            return;
        }
        deleteAmenityIconPathQuietly(replacement.backup());

        Path iconDirectory = resolveAmenityIconDirectory(false);
        if (iconDirectory == null) {
            return;
        }
        for (String extension : AMENITY_ICON_EXTENSIONS) {
            if (extension.equals(replacement.extension())) {
                continue;
            }
            Path stale = iconDirectory
                    .resolve(amenityIconFileName(replacement.code(), extension)).normalize();
            ensureContained(iconDirectory, stale);
            deleteAmenityIconPathQuietly(stale);
        }
    }

    /** 교체 취소. 새로 쓴 파일을 지우고 백업해 둔 기존 아이콘을 되돌린다. */
    public void rollbackAmenityIconReplacement(AmenityIconReplacement replacement) {
        if (replacement == null) {
            return;
        }
        deleteAmenityIconPathQuietly(replacement.target());
        restoreAmenityIconBackup(replacement.backup(), replacement.target());
    }

    private void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void restoreAmenityIconBackup(Path backup, Path destination) {
        if (backup == null) {
            return;
        }
        try {
            if (Files.exists(backup)) {
                moveIntoPlace(backup, destination);
            }
        } catch (IOException ignored) {
            // 되돌리기까지 실패하면 백업 파일은 남겨 둔다. 원본 실패를 우선 전달한다.
        }
    }

    /**
     * 아이콘 교체 진행 상태. 트랜잭션 결과에 따라 commit/rollback 에 그대로 넘긴다.
     *
     * @param iconUrl   새 아이콘 웹 경로
     * @param backup    같은 이름의 기존 파일 백업 (없으면 null)
     */
    public record AmenityIconReplacement(
            String iconUrl, String code, String extension, Path target, Path backup) {
    }

    /** 롤백 정리용. 이번 등록에서 만든 아이콘만 지운다(확장자는 포맷마다 다르다). */
    public boolean deleteAmenityIcon(String code) {
        Path iconDirectory = resolveAmenityIconDirectory(false);
        if (iconDirectory == null) {
            return false;
        }

        boolean deleted = false;
        for (String extension : AMENITY_ICON_EXTENSIONS) {
            Path target = iconDirectory.resolve(amenityIconFileName(code, extension)).normalize();
            ensureContained(iconDirectory, target);
            try {
                deleted |= Files.deleteIfExists(target);
            } catch (IOException exception) {
                throw new IllegalStateException("편의시설 아이콘 파일 삭제에 실패했습니다.", exception);
            }
        }
        return deleted;
    }

    /** DB 에 code 가 없어도 파일만 남아 있을 수 있으므로 확장자 전체를 확인한다. */
    private void ensureNoExistingAmenityIcon(Path iconDirectory, String code) {
        for (String extension : AMENITY_ICON_EXTENSIONS) {
            Path existing = iconDirectory.resolve(amenityIconFileName(code, extension)).normalize();
            ensureContained(iconDirectory, existing);
            if (Files.exists(existing)) {
                throw amenityIconAlreadyExists(null);
            }
        }
    }

    private IllegalStateException amenityIconAlreadyExists(Throwable cause) {
        return new IllegalStateException(
                "같은 코드의 아이콘 파일이 이미 있습니다. 다른 코드를 사용해 주세요.", cause);
    }

    /** code -> 파일명. 경로 문자가 섞일 수 없도록 형식을 먼저 확인한다. */
    private String amenityIconFileName(String code, String extension) {
        if (code == null || !AMENITY_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("올바르지 않은 편의시설 코드입니다.");
        }
        return code.toLowerCase(Locale.ROOT) + "." + extension;
    }

    /** 실제로 decode 되는 이미지인지 확인하고, 저장에 쓸 이미지를 그대로 돌려준다. */
    private BufferedImage decodeAmenityIcon(MultipartFile file, AmenityIconFormat expected) {
        try (InputStream input = file.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw unsupportedAmenityIcon();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw unsupportedAmenityIcon();
            }

            ImageReader reader = readers.next();
            try {
                if (expected != AmenityIconFormat.of(reader.getFormatName())) {
                    throw unsupportedAmenityIcon();
                }
                reader.setInput(imageInput);
                if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                    throw unsupportedAmenityIcon();
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw unsupportedAmenityIcon();
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw unsupportedAmenityIcon();
        }
    }

    private Path resolveAmenityIconDirectory(boolean create) {
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            if (create) {
                Files.createDirectories(uploadRoot);
            } else if (Files.notExists(uploadRoot)) {
                return null;
            }

            Path realUploadRoot = uploadRoot.toRealPath();
            Path iconDirectory = realUploadRoot.resolve(AMENITY_ICON_DIRECTORY).normalize();
            ensureContained(realUploadRoot, iconDirectory);
            if (create) {
                Files.createDirectories(iconDirectory);
            } else if (Files.notExists(iconDirectory)) {
                return null;
            }

            Path realIconDirectory = iconDirectory.toRealPath();
            ensureContained(realUploadRoot, realIconDirectory);
            return realIconDirectory;
        } catch (IOException exception) {
            throw new IllegalStateException("편의시설 아이콘 저장 경로를 준비할 수 없습니다.", exception);
        }
    }

    private void deleteAmenityIconPathQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 원래 저장 실패를 우선 전달한다.
        }
    }

    private void ensureContained(Path root, Path target) {
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("허용된 업로드 경로를 벗어날 수 없습니다.");
        }
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    /** 여행지 직접 업로드 허용 포맷 (KTO 사진과 동일하게 JPEG/PNG 만 허용) */
    private enum DestinationImageFormat {
        JPEG("jpg"),
        PNG("png");

        private final String extension;

        DestinationImageFormat(String extension) {
            this.extension = extension;
        }

        private static DestinationImageFormat of(String formatName) {
            if ("JPEG".equalsIgnoreCase(formatName) || "JPG".equalsIgnoreCase(formatName)) {
                return JPEG;
            }
            if ("PNG".equalsIgnoreCase(formatName)) {
                return PNG;
            }
            throw new UnsupportedImageFormatException(UNSUPPORTED_DESTINATION_IMAGE_MESSAGE);
        }
    }

    /** 편의시설 아이콘 업로드 허용 포맷. 저장은 항상 PNG 로 한다. */
    private enum AmenityIconFormat {
        PNG,
        JPEG;

        private static AmenityIconFormat of(String formatName) {
            if ("PNG".equalsIgnoreCase(formatName)) {
                return PNG;
            }
            if ("JPEG".equalsIgnoreCase(formatName) || "JPG".equalsIgnoreCase(formatName)) {
                return JPEG;
            }
            throw new UnsupportedImageFormatException(UNSUPPORTED_AMENITY_ICON_MESSAGE);
        }
    }

    /**
     * 저장 확장자와, 이 런타임에서 다시 구울 수 있는지.
     *
     * <p>WEBP 는 표준 ImageIO 가 읽지도 쓰지도 못해 {@code imageIoName} 이 없다.
     * 줄이지 못하므로 올라온 그대로 저장한다. 다른 형식으로 구워 {@code .webp} 이름을
     * 붙이는 일은 하지 않는다 — 이름과 속이 다른 파일이 된다.
     */
    private enum ThumbnailFormat {
        JPEG("jpg", "jpeg", false),
        PNG("png", "png", true),
        WEBP("webp", null, true);

        private final String extension;
        /** ImageIO 형식 이름. 다시 구울 수 없으면 null. */
        private final String imageIoName;
        /** 투명도를 지닐 수 있는 형식인지. */
        private final boolean keepsAlpha;

        ThumbnailFormat(String extension, String imageIoName, boolean keepsAlpha) {
            this.extension = extension;
            this.imageIoName = imageIoName;
            this.keepsAlpha = keepsAlpha;
        }
    }

    private enum DiaryStickerImageFormat {
        PNG("png"),
        WEBP("webp");

        private final String extension;

        DiaryStickerImageFormat(String extension) {
            this.extension = extension;
        }
    }
}
