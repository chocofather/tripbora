package com.tripbora.controller.travelplan;

import com.tripbora.dto.TravelPlanChatMessageDto;
import com.tripbora.service.travelplan.TravelPlanChatImageService;
import com.tripbora.service.travelplan.TravelPlanValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/travel-plans/{travelPlanId:\\d+}/chat")
public class TravelPlanChatImageController {
    private final TravelPlanChatImageService service;

    // 기존 SecurityFilterChain의 authenticated 및 기본 CSRF 검사를 그대로 따른다.
    @PostMapping(value="/image-messages", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public TravelPlanChatMessageDto send(@PathVariable Long travelPlanId,
            @RequestParam(defaultValue="") String content, @RequestParam String clientRequestId,
            @RequestParam("images") List<MultipartFile> images, Principal principal) {
        return service.send(principal,travelPlanId,content,clientRequestId,images);
    }

    @GetMapping("/messages/{messageId:\\d+}/images/{imageId:\\d+}")
    public ResponseEntity<Resource> image(@PathVariable Long travelPlanId, @PathVariable Long messageId,
            @PathVariable Long imageId, Principal principal) {
        var image = service.image(principal,travelPlanId,messageId,imageId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(new FileSystemResource(image.path()));
    }

    @ExceptionHandler({TravelPlanValidationException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String,String>> invalid(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of("message",exception.getMessage()));
    }
}
