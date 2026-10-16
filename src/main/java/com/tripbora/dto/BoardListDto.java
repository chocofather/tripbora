package com.tripbora.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
public class BoardListDto {
    private Long id;
    private Long userId;
    private String boardType;      // "post" or "course"
    private String postType;       // (post만) "tip" "question"
    private String title;
    private int commentCount;
    private String nickname;
    /** 최종 탈퇴(DEACTIVATED) 회원이면 true. 화면은 nickname 대신 공통 문구를 쓴다. */
    private boolean writerWithdrawn;
    private String createdAt;
    private int views;
    private int bookmarkCount;
    private List<CourseThemeDto> themes = List.of();
}
