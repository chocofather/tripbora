package com.tripbora.repository.board;

import com.tripbora.dto.BoardListDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BoardMapper {

    List<BoardListDto> findPublicBoardListByUserId(
            @Param("userId") Long userId, @Param("boardType") String boardType,
            @Param("postType") String postType, @Param("offset") long offset,
            @Param("limit") int limit);

    int countPublicBoardByUserId(@Param("userId") Long userId,
            @Param("boardType") String boardType, @Param("postType") String postType);

    List<BoardListDto> findBoardList(
            @Param("boardType") String boardType,
            @Param("postType") String postType,
            @Param("scope") String scope,
            @Param("countryId") Long countryId,
            @Param("sort") String sort,
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    int countBoard(
            @Param("boardType") String boardType,
            @Param("postType") String postType,
            @Param("scope") String scope,
            @Param("countryId") Long countryId
    );

    List<BoardListDto> findBoardListByUserId(
            @Param("userId") Long userId,
            @Param("boardType") String boardType,
            @Param("postType") String postType,
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    int countBoardByUserId(
            @Param("userId") Long userId,
            @Param("boardType") String boardType,
            @Param("postType") String postType
    );
}
