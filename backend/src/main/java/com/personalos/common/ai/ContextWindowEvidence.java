package com.personalos.common.ai;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Passive, runtime-only evidence. Successful input is a lower bound, never a claimed maximum. */
public final class ContextWindowEvidence {
    private static final int MAX_MODELS = 256;
    private static final int MAX_LIMIT = 32 * 1024 * 1024;
    private static final Pattern CONTEXT_LIMIT = Pattern.compile(
            "(?:maximum\\s+context\\s+length|context\\s+window\\s+(?:limit|maximum)|max(?:imum)?[_ ]context[_ ](?:length|window))"
                    + "[^0-9]{0,32}([0-9][0-9,]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT_RANGE = Pattern.compile(
            "input\\s+length\\s+should\\s+be\\s*[^0-9]{0,8}1\\s*,\\s*([0-9][0-9,]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT_LIMIT = Pattern.compile(
            "(?:max_input_tokens|maximum\\s+input\\s+(?:length|tokens)|input\\s+token\\s+limit)"
                    + "[^0-9]{0,32}([0-9][0-9,]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT_COMPARISON = Pattern.compile(
            "prompt\\s+is\\s+too\\s+long[^\\n]{0,80}?>\\s*([0-9][0-9,]*)\\s*(?:maximum|tokens|token)", Pattern.CASE_INSENSITIVE);

    public record Limits(Integer contextTokens, Integer inputTokens) { }
    public record Snapshot(Integer contextTokens, Integer inputTokens, boolean explicitInputLimit,
                           int verifiedInputTokens, double tokenRatio) {
        static Snapshot empty() { return new Snapshot(null, null, false, 0, 1.0); }
    }
    private record Key(long generation, String model) { }
    private final ConcurrentHashMap<Key, Snapshot> evidence = new ConcurrentHashMap<>();
    private long generation;

    public synchronized long generation() { return generation; }

    public synchronized void reset() {
        generation++;
        evidence.clear();
    }

    public synchronized Snapshot snapshot(String model) {
        return snapshot(generation, model);
    }

    public synchronized Snapshot snapshot(long epoch, String model) {
        return evidence.getOrDefault(new Key(epoch, model == null ? "" : model), Snapshot.empty());
    }

    public synchronized void accepted(long epoch, String model, int estimatedTokens, int actualTokens) {
        if (epoch != generation || model == null || actualTokens <= 0 || actualTokens > MAX_LIMIT) return;
        Key key = key(epoch, model);
        Snapshot previous = evidence.getOrDefault(key, Snapshot.empty());
        // Only raise estimates. A short successful request cannot establish a larger window,
        // and tokenizer estimates must not become less conservative based on one sample.
        double ratio = estimatedTokens >= 256 ? Math.max(1.0, (double) actualTokens / estimatedTokens) : 1.0;
        evidence.put(key, new Snapshot(previous.contextTokens(), previous.inputTokens(), previous.explicitInputLimit(),
                Math.max(previous.verifiedInputTokens(), actualTokens), Math.max(previous.tokenRatio(), ratio)));
    }

    public synchronized void rejected(long epoch, String model, Throwable error, int attemptedInputTokens) {
        if (epoch != generation || model == null || !isOverflow(error)) return;
        Limits limits = limitsFrom(error);
        Key key = key(epoch, model);
        Snapshot previous = evidence.getOrDefault(key, Snapshot.empty());
        Integer context = minimum(previous.contextTokens(), limits.contextTokens());
        Integer input = previous.inputTokens();
        boolean explicit = previous.explicitInputLimit();
        if (limits.inputTokens() != null) {
            input = minimum(input, limits.inputTokens());
            // If an earlier conservative estimate is smaller, it still isn't an explicit endpoint limit.
            explicit = input.equals(limits.inputTokens()) || (previous.explicitInputLimit() && input.equals(previous.inputTokens()));
        } else if (limits.contextTokens() == null && attemptedInputTokens >= 1024
                && attemptedInputTokens > previous.verifiedInputTokens()) {
            int reduced = Math.max(Math.max(1024, previous.verifiedInputTokens() + 512), (int) (attemptedInputTokens * 0.65));
            input = minimum(input, reduced);
            if (!input.equals(previous.inputTokens())) explicit = false;
        }
        evidence.put(key, new Snapshot(context, input, explicit, previous.verifiedInputTokens(), previous.tokenRatio()));
    }

    private Key key(long epoch, String model) {
        Key key = new Key(epoch, model);
        if (!evidence.containsKey(key) && evidence.size() >= MAX_MODELS) evidence.clear();
        return key;
    }

    private static Integer minimum(Integer first, Integer second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.min(first, second);
    }

    public static boolean isOverflow(Throwable error) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("context_length_exceeded") || message.contains("maximum context length")
                    || message.contains("context window exceeded") || message.contains("prompt is too long")
                    || message.contains("input is too long") || message.contains("input length should be")
                    || message.contains("exceeds the context window") || message.contains("too many input tokens")
                    || (message.contains("max_input_tokens") && message.contains("exceed"))) return true;
        }
        return false;
    }

    public static Limits limitsFrom(Throwable error) {
        Integer context = null;
        Integer input = null;
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            context = minimum(context, read(CONTEXT_LIMIT, message));
            input = minimum(input, read(INPUT_RANGE, message));
            input = minimum(input, read(INPUT_LIMIT, message));
            input = minimum(input, read(PROMPT_COMPARISON, message));
        }
        return new Limits(context, input);
    }

    private static Integer read(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        Integer result = null;
        while (matcher.find()) {
            try {
                long value = Long.parseLong(matcher.group(1).replace(",", ""));
                if (value >= 1024 && value <= MAX_LIMIT) result = minimum(result, (int) value);
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }
}
