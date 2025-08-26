package regera.app.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;
import regera.app.springai.dto.ChatRequest;

import java.util.Objects;

@Service
public class ChatService {
    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String chat(ChatRequest request) {
        SystemMessage systemMessage = new SystemMessage("""
                You are Regera.AI
                You should response with a formal tone and provide a concise answer.
            """);

        UserMessage userMessage = new UserMessage(request.getMessage());

        Prompt prompt = new Prompt(systemMessage, userMessage);

        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    public String chatWithImage(ChatRequest request) {
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(Objects.requireNonNull(request.getImage().getContentType())))
                .data(request.getImage().getResource())
                .build();
    
        return chatClient.prompt()
            .system("You are Regera.AI. You should respond with a formal tone and provide a concise answer.")
            .user(promptUserSpec
                    -> promptUserSpec.media(media)
                    .text(request.getMessage()))
            .call()
            .content();
    }

    public String chatWithImage(MultipartFile image, String message) {
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(Objects.requireNonNull(image.getContentType())))
                .data(image.getResource())
                .build();

        ChatOptions chatOptions = ChatOptions.builder()
                .temperature(1D)
                .build();

        return chatClient.prompt()
                .system("You are Regera.AI. be creative.")
                .options(chatOptions)
                .user(promptUserSpec
                        -> promptUserSpec.media(media)
                        .text(message))
                .call()
                .content();
    }
}
