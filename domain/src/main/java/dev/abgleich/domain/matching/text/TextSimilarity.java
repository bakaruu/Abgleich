package dev.abgleich.domain.matching.text;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Comparisons of untrusted bank text with invoice data. Scores are decimals, never doubles (B01), so
 * a threshold like 0.90 means exactly the same on every JVM.
 */
public final class TextSimilarity {

    private static final MathContext PRECISION = MathContext.DECIMAL64;
    private static final BigDecimal WINKLER_SCALING = new BigDecimal("0.1");
    private static final int WINKLER_PREFIX = 4;

    /** Company forms carry no identity: "Brunner & Co. AG" and "Brunner und Co" are the same payer. */
    private static final Set<String> LEGAL_FORMS = Set.of(
            "AG", "GMBH", "SA", "SL", "SLU", "SARL", "SAGL", "KG", "OG", "EG", "CO", "UND", "Y", "ET", "E", "AND",
            "LTD", "LLC", "SE", "SAS", "SRL", "SPA", "BV", "NV", "COOP", "SCOOP");

    private TextSimilarity() {
    }

    /** Upper case, without accents or punctuation, single spaces: "Zürcher Bäckerei" becomes "ZURCHER BACKEREI". */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return withoutAccents.toUpperCase(Locale.ROOT)
                // "S.L." and "SL" are the same abbreviation; other punctuation separates words.
                .replaceAll("\\b([A-Z])\\.(?=[A-Z]\\b)", "$1")
                .replace('&', ' ')
                .replaceAll("[^A-Z0-9]+", " ")
                .strip();
    }

    /** A name reduced to what identifies it: normalized and without legal forms. */
    public static String nameKey(String name) {
        return Arrays.stream(normalize(name).split(" "))
                .filter(token -> !token.isEmpty() && !LEGAL_FORMS.contains(token))
                .collect(Collectors.joining(" "));
    }

    /**
     * Similarity of a payer and a debtor name, from 0.0000 to 1.0000: every word of each name must have
     * a close Jaro-Winkler partner in the other, and the score is the weakest pair.
     *
     * <p>Plain Jaro-Winkler over the whole name rewards a shared prefix: "PINTURAS SOL" and "PINTURAS LUNA"
     * would score 0.92 and pass a 0.90 threshold. Comparing word by word, in both directions, keeps
     * word order and typos tolerable ("Muster Handwerck") while a different word sinks the score.
     */
    public static BigDecimal nameSimilarity(String first, String second) {
        String[] firstWords = nameKey(first).split(" ");
        String[] secondWords = nameKey(second).split(" ");
        if (firstWords[0].isEmpty() || secondWords[0].isEmpty()) {
            return BigDecimal.ZERO.setScale(4);
        }
        BigDecimal forward = weakestBestPartner(firstWords, secondWords);
        BigDecimal backward = weakestBestPartner(secondWords, firstWords);
        return forward.min(backward);
    }

    private static BigDecimal weakestBestPartner(String[] words, String[] candidates) {
        BigDecimal weakest = BigDecimal.ONE.setScale(4);
        for (String word : words) {
            BigDecimal best = BigDecimal.ZERO.setScale(4);
            for (String candidate : candidates) {
                best = best.max(jaroWinkler(word, candidate));
            }
            weakest = weakest.min(best);
        }
        return weakest;
    }

    static BigDecimal jaroWinkler(String first, String second) {
        if (first.isEmpty() || second.isEmpty()) {
            return BigDecimal.ZERO.setScale(4);
        }
        if (first.equals(second)) {
            return BigDecimal.ONE.setScale(4);
        }
        int window = Math.max(0, Math.max(first.length(), second.length()) / 2 - 1);
        boolean[] firstMatched = new boolean[first.length()];
        boolean[] secondMatched = new boolean[second.length()];
        int matches = 0;
        for (int i = 0; i < first.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(second.length() - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!secondMatched[j] && first.charAt(i) == second.charAt(j)) {
                    firstMatched[i] = true;
                    secondMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        int halfTranspositions = 0;
        int k = 0;
        for (int i = 0; i < first.length(); i++) {
            if (firstMatched[i]) {
                while (!secondMatched[k]) {
                    k++;
                }
                if (first.charAt(i) != second.charAt(k)) {
                    halfTranspositions++;
                }
                k++;
            }
        }
        BigDecimal m = BigDecimal.valueOf(matches);
        BigDecimal jaro = m.divide(BigDecimal.valueOf(first.length()), PRECISION)
                .add(m.divide(BigDecimal.valueOf(second.length()), PRECISION))
                .add(m.subtract(BigDecimal.valueOf(halfTranspositions / 2)).divide(m, PRECISION))
                .divide(BigDecimal.valueOf(3), PRECISION);
        int prefix = 0;
        while (prefix < Math.min(WINKLER_PREFIX, Math.min(first.length(), second.length()))
                && first.charAt(prefix) == second.charAt(prefix)) {
            prefix++;
        }
        BigDecimal winkler = jaro.add(BigDecimal.valueOf(prefix).multiply(WINKLER_SCALING)
                .multiply(BigDecimal.ONE.subtract(jaro)), PRECISION);
        return winkler.setScale(4, RoundingMode.HALF_EVEN);
    }

    /** Whether two references differ by at most one inserted, deleted or replaced character. */
    public static boolean withinOneEdit(String first, String second) {
        if (Math.abs(first.length() - second.length()) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        int edits = 0;
        while (i < first.length() && j < second.length()) {
            if (first.charAt(i) == second.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++edits > 1) {
                return false;
            }
            if (first.length() > second.length()) {
                i++;
            } else if (first.length() < second.length()) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return edits + (first.length() - i) + (second.length() - j) <= 1;
    }
}
