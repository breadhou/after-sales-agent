package com.mall.agent.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ranks source text by exact Chinese bigram and Latin/numeric term overlap. */
public final class TermRanker {

    private static final Pattern TERM = Pattern.compile("\\p{IsHan}+|[\\p{IsAlphabetic}\\p{IsDigit}]+");

    public List<String> rank(String originalQuestion, Map<String, String> sourceIdToText) {
        Objects.requireNonNull(originalQuestion, "originalQuestion");
        Objects.requireNonNull(sourceIdToText, "sourceIdToText");
        Set<String> queryTerms = new HashSet<>(terms(originalQuestion));
        if (queryTerms.isEmpty()) return List.of();

        List<RankedSource> ranked = new ArrayList<>();
        for (Map.Entry<String, String> source : sourceIdToText.entrySet()) {
            Set<String> sourceTerms = new HashSet<>(terms(source.getValue()));
            int score = 0;
            for (String queryTerm : queryTerms) {
                if (sourceTerms.contains(queryTerm)) score++;
            }
            if (score > 0) ranked.add(new RankedSource(source.getKey(), score));
        }
        return ranked.stream()
                .sorted(Comparator.comparingInt(RankedSource::score).reversed()
                        .thenComparing(RankedSource::sourceId))
                .map(RankedSource::sourceId)
                .toList();
    }

    private static List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = TERM.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (isHanRun(token)) {
                int[] codePoints = token.codePoints().toArray();
                for (int index = 0; index + 1 < codePoints.length; index++) {
                    terms.add(new String(codePoints, index, 2));
                }
            } else {
                terms.add(token);
            }
        }
        return terms;
    }

    private static boolean isHanRun(String token) {
        return token.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN);
    }

    private record RankedSource(String sourceId, int score) { }
}
