package com.tripbora.controller.course;

import com.tripbora.web.CourseUrls;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/** 과거 URL 호환만 담당한다. 조회·저장 비즈니스 로직은 canonical controller에만 둔다. */
@Controller
@RequestMapping("/course")
public class LegacyCourseController {
    @GetMapping("/{id}")
    public ResponseEntity<Void> detail(@PathVariable Long id, HttpServletRequest request) {
        return redirect(CourseUrls.detail(id), request, HttpStatus.MOVED_PERMANENTLY);
    }

    @GetMapping("/write")
    public ResponseEntity<Void> create(HttpServletRequest request) {
        return redirect(CourseUrls.NEW, request, HttpStatus.MOVED_PERMANENTLY);
    }

    @GetMapping("/{id}/edit")
    public ResponseEntity<Void> edit(@PathVariable Long id, HttpServletRequest request) {
        return redirect(CourseUrls.edit(id), request, HttpStatus.MOVED_PERMANENTLY);
    }

    @GetMapping("/{id}/translation")
    public ResponseEntity<Void> translation(@PathVariable Long id, HttpServletRequest request) {
        return redirect(CourseUrls.translation(id), request, HttpStatus.MOVED_PERMANENTLY);
    }

    @PostMapping("/write")
    public ResponseEntity<Void> oldCreate(HttpServletRequest request) {
        return redirect(CourseUrls.NEW, request, HttpStatus.TEMPORARY_REDIRECT);
    }

    @PostMapping("/{id}/edit")
    public ResponseEntity<Void> oldEdit(@PathVariable Long id, HttpServletRequest request) {
        return redirect(CourseUrls.edit(id), request, HttpStatus.TEMPORARY_REDIRECT);
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> oldDelete(@PathVariable Long id, HttpServletRequest request) {
        return redirect(CourseUrls.delete(id), request, HttpStatus.TEMPORARY_REDIRECT);
    }

    private ResponseEntity<Void> redirect(String path, HttpServletRequest request, HttpStatus status) {
        // commentId/lang 등 deep link query는 재인코딩 없이 보존하고 외부 주소는 만들지 않는다.
        String query = request.getQueryString();
        URI target = UriComponentsBuilder.fromPath(path).query(query).build(true).toUri();
        return ResponseEntity.status(status).location(target).build();
    }
}
