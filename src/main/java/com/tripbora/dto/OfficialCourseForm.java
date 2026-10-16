package com.tripbora.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;
import java.util.ArrayList;
import java.util.List;

/** 관리자 입력 전용. 작성자와 코스 종류는 서버에서 결정한다. */
@Data
public class OfficialCourseForm {
    private String title;
    private String summary;
    private String status = "DRAFT";
    private MultipartFile coverImage;
    private boolean removeCoverImage;
    private List<Long> themeIds = new ArrayList<>();
    private List<CourseDayForm> days = new ArrayList<>();
}
