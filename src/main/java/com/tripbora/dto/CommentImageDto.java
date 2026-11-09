package com.tripbora.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CommentImageDto {
    private Long id;
    private String imageUrl;

    public CommentImageDto(String imageUrl) { this(null, imageUrl); }
}
