package regera.app.springai.reviewer;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import com.fasterxml.jackson.databind.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
public class GeminiReviewer {
    static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
    static final String API_KEY = getenvOrFail("GEMINI_API_KEY");
    static final String MODEL = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-1.5-pro");
    static final String DIFF_FILE = System.getenv().getOrDefault("DIFF_FILE", "diff.patch");
    static final int MAX_PROMPT_CHARS = Integer.parseInt(System.getenv().getOrDefault("MAX_PROMPT_CHARS", "28000"));
    static final Pattern ALLOW = Pattern.compile(System.getenv().getOrDefault("FILE_ALLOWLIST_REGEX", ".*\\.(java|kt|ts|tsx|js|jsx|py|go|rb|cs)$"), Pattern.CASE_INSENSITIVE);
    static final Pattern DENY = Pattern.compile(System.getenv().getOrDefault("FILE_DENYLIST_REGEX", "(^|/)dist/|(^|/)build/|\\.min\\.|\\.lock$|(^|/)node_modules/|(^|/)vendor/"), Pattern.CASE_INSENSITIVE);
    static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + API_KEY;

    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(30))
            .build();

    record FileDiff(String path, String content) {}
    record Finding(String file, Integer line, String severity, String title, String detail, String suggestion) {}

    public static void main(String[] args) throws Exception {
        log.info("Starting Gemini code reviewer...");
        final String rawDiff = Files.exists(Path.of(DIFF_FILE)) ? Files.readString(Path.of(DIFF_FILE)) : "";
        if (rawDiff.isBlank()) {
            log.info("### AI Review\n\n_No changes detected._");
            return;
        }

        String BLOOOOgg = "fdf";
        System.out.println("BLOOOOOO");

        List<FileDiff> diffs = splitByFile(rawDiff);
        diffs = filterFiles(diffs);

        List<List<FileDiff>> batches = batchByCharLimit(diffs, MAX_PROMPT_CHARS);

        List<Finding> findings = new ArrayList<>();
        for (List<FileDiff> batch : batches) {
            String prompt = buildPrompt(batch);
            List<Finding> part = callGemini(prompt);
            findings.addAll(part);
            Thread.sleep(400L);
        }

        String report = renderMarkdown(findings);
        Files.writeString(Path.of("review.md"), report);
        log.info(report);
    }

    static String getenvOrFail(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing env: " + k);
        return v;
    }

    static List<FileDiff> splitByFile(String diff) {
        String[] chunks = diff.split("(?m)^diff --git ");
        List<FileDiff> out = new ArrayList<>();
        for (String c : chunks) {
            if (c.isBlank()) continue;
            // Header dạng: a/path b/path
            int nl = c.indexOf('\n');
            String header = nl > 0 ? c.substring(0, nl) : c;
            // Parse path b/
            String path = header.replaceFirst("^a/[^ ]+ b/", "");
            if (path.isBlank()) continue;
            out.add(new FileDiff(path.trim(), "diff --git " + c));
        }
        return out;
    }

    static List<FileDiff> filterFiles(List<FileDiff> in) {
        List<FileDiff> out = new ArrayList<>();
        for (FileDiff f : in) {
            String p = f.path;
            if (!ALLOW.matcher(p).find()) continue;
            if (DENY.matcher(p).find()) continue;
            if (f.content.length() > 120_000) continue;
            out.add(f);
        }
        return out;
    }

    static List<List<FileDiff>> batchByCharLimit(List<FileDiff> files, int limit) {
        List<List<FileDiff>> batches = new ArrayList<>();
        List<FileDiff> cur = new ArrayList<>();
        int curLen = 0;
        for (FileDiff f : files) {
            int add = f.content.length() + f.path.length() + 64;
            if (!cur.isEmpty() && curLen + add > limit) {
                batches.add(cur);
                cur = new ArrayList<>();
                curLen = 0;
            }
            cur.add(f);
            curLen += add;
        }
        if (!cur.isEmpty()) batches.add(cur);
        return batches;
    }

    static String buildPrompt(List<FileDiff> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
        You are a **senior code reviewer**. 
        Your job: analyze the following diffs and report only on the **changed lines**, focusing on:
        - **Bugs** (logic errors, null safety, concurrency, resource leaks, incorrect API usage).
        - **Security** (OWASP Top 10, injections, unsafe deserialization, weak crypto).
        - **Performance** (unnecessary complexity, inefficient loops, memory issues).
        - **Correctness** (violations of language contracts, edge cases).
        - **Maintainability & Clean Code** (readability, duplication, naming, cohesion).
        - **Code smells** explicitly including:
            * Long methods or classes
            * Deeply nested conditionals
            * Duplicated code
            * Magic numbers / hardcoded values
            * Unused variables, imports, or parameters
            * Poor naming conventions
            * Excessive comments or commented-out code
            * Primitive obsession (raw strings, ints where enums/classes are better)
            * Swallowing exceptions (empty catch)
            * Logging issues (missing logs, sensitive data in logs)
            * Inconsistent formatting or style
            
        Return STRICTLY the following JSON schema:
        {
          "findings": [
            {
              "file": "string",           // relative path
              "line": 123,                // 1-based line if identifiable, else null
              "severity": "INFO|MINOR|MAJOR|CRITICAL",
              "title": "short title",
              "detail": "what & why (concise, actionable)",
              "suggestion": "optional code suggestion or fix"
            }
          ]
        }
    
        Rules:
        - Only comment on the changed hunks from the diff.
        - If a smell cannot be proven from diff alone, skip it.
        - Be concise but actionable.
        - If unsure about the severity, default to MINOR.
    
        Below are the diffs (unified=0):
        """);
        for (FileDiff f : batch) {
            sb.append("\n===== FILE: ").append(f.path).append(" =====\n");
            sb.append(f.content).append("\n");
        }
        return sb.toString();
    }

    static List<Finding> callGemini(String prompt) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );
        Request req = new Request.Builder()
                .url(ENDPOINT)
                .post(RequestBody.create(M.writeValueAsBytes(body), MediaType.parse("application/json")))
                .build();

        for (int i = 0; i < 3; i++) {
            try (Response resp = HTTP.newCall(req).execute()) {
                if (resp.code() == 429 || resp.code() >= 500) {
                    Thread.sleep(600L * (i + 1));
                    continue;
                }
                if (!resp.isSuccessful()) throw new IOException("Gemini error: " + resp);

                String raw = Objects.requireNonNull(resp.body()).string();
                log.info("Gemini raw response:\n{}", raw);

                // Trả thẳng text, không parse JSON
                // Lấy tất cả text trong candidates/parts
                JsonNode root = M.readTree(raw);
                StringBuilder sb = new StringBuilder();
                for (JsonNode candidate : root.path("candidates")) {
                    for (JsonNode part : candidate.path("content").path("parts")) {
                        String txt = part.path("text").asText("");
                        if (!txt.isBlank()) sb.append(txt).append("\n\n");
                    }
                }

                // Trả findings kiểu "fake" với suggestion chứa toàn bộ text
                if (sb.isEmpty()) return List.of();
                return List.of(new Finding(
                        "RAW_OUTPUT",
                        null,
                        "INFO",
                        "Gemini raw response",
                        "Gemini did not return structured JSON. See suggestion for raw text.",
                        sb.toString()
                ));
            }
        }
        return List.of();
    }

    static String renderMarkdown(List<Finding> list) {
        if (list.isEmpty()) return "### 🤖 Gemini Review\n\n✅ No issues found in changed lines.";

        StringBuilder sb = new StringBuilder();
        sb.append("### AI Review (Gemini)\n\n");
        // Summary by severity
        Map<String, Long> tally = new TreeMap<>();
        list.forEach(f -> tally.put(f.severity, tally.getOrDefault(f.severity, 0L) + 1));
        sb.append("**Summary:** ");
        tally.forEach((k,v) -> sb.append(k).append(": ").append(v).append("  "));
        sb.append("\n\n");
        if (list.stream().anyMatch(f -> "CRITICAL".equalsIgnoreCase(f.severity))) {
            sb.append("> SEVERITY: CRITICAL\n\n");
        }

        // Group by file
        list.stream()
                .sorted(Comparator.<Finding, String>comparing(f -> f.file)
                        .thenComparing(f -> f.line == null ? Integer.MAX_VALUE : f.line))
                .forEach(f -> {
                    sb.append("**").append(f.file).append(f.line==null?"":(":" + f.line)).append("** — ");
                    sb.append("`").append(f.severity).append("` ");
                    sb.append("**").append(escape(f.title)).append("**\n\n");
                    if (f.detail != null && !f.detail.isBlank()) {
                        sb.append(f.detail).append("\n\n");
                    }
                    if (f.suggestion != null && !f.suggestion.isBlank()) {
                        // nếu là suggestion code, có thể dùng ```suggestion cho inline (cần API review)
                        sb.append("_Suggestion:_\n");
                        sb.append("```").append("\n").append(f.suggestion).append("\n```").append("\n\n");
                    }
                });

        sb.append("\n> _Automated review. Please verify before applying suggestions._\n");
        return sb.toString();
    }

    static String escape(String s) { return s == null ? "" : s.replace("\n", " ").trim(); }
}
