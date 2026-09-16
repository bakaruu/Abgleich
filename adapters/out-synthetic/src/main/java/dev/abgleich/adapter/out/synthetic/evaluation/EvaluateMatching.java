package dev.abgleich.adapter.out.synthetic.evaluation;

import dev.abgleich.domain.matching.Matcher;
import dev.abgleich.domain.matching.MatchingPolicy;

/** Entry point of {@code ./gradlew evaluateMatching}. Exits with status 1 if any automatic confirmation is wrong. */
public final class EvaluateMatching {

    private EvaluateMatching() {
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : MatchingDatasetGenerator.DEFAULT_SEED;
        MatchingEvaluation.Report report = MatchingEvaluation.evaluate(
                new MatchingDatasetGenerator(seed).generate(), new Matcher(MatchingPolicy.defaults()));
        System.out.println(report.render());
        if (report.wrongAutoConfirmations() > 0) {
            System.exit(1);
        }
    }
}
