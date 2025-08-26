package regera.app.springai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import regera.app.springai.dto.ChatRequest;
import regera.app.springai.service.ChatService;

@RestController
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;

    @PostMapping("/chat")
    String chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @PostMapping("/image-chat")
    String chatWithImage(@RequestParam MultipartFile image, @RequestParam String message) {
        return chatService.chatWithImage(image, message);
    }
}
