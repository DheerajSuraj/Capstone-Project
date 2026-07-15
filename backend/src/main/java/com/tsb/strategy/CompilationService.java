package com.tsb.strategy;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Diagnostic;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The strategy feature's facade over the pure compiler: runs
 * lex → parse → analyze and aggregates every phase's diagnostics into one
 * outcome. This is the single entry point for compiling TSL anywhere in the
 * platform — the /api/compile endpoint now, backtest submission and the
 * competition worker later. One front door, as the roadmap demands.
 *
 * <p>Phase gating mirrors the CLI: each phase only runs if the previous one
 * produced usable output with no errors. Analyzing a best-effort AST that
 * the parser already flagged would produce noise diagnostics about
 * constructs the user never wrote — the cascade-suppression principle,
 * applied between phases.
 *
 * <p>Deliberately stateless (no fields): safe as a Spring singleton under
 * concurrent requests, and trivially testable with {@code new
 * CompilationService()} — no Spring context needed.
 */
@Service
public class CompilationService {

    /** The aggregated result of all compiler phases. */
    public record Outcome(
            Optional<CompiledStrategy> strategy,
            List<Diagnostic> diagnostics
    ) {
        public boolean ok() {
            return strategy.isPresent()
                    && diagnostics.stream().noneMatch(Diagnostic::isError);
        }
    }

    public Outcome compile(String source) {
        List<Diagnostic> all = new ArrayList<>();

        Lexer.LexResult lexed = new Lexer(source).scan();
        all.addAll(lexed.diagnostics());

        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        all.addAll(parsed.diagnostics());

        boolean cleanSoFar = all.stream().noneMatch(Diagnostic::isError);
        if (!cleanSoFar || parsed.strategy().isEmpty()) {
            return new Outcome(Optional.empty(), List.copyOf(all));
        }

        Analyzer.AnalysisResult analyzed =
                new Analyzer(parsed.strategy().get()).analyze();
        all.addAll(analyzed.diagnostics());

        return new Outcome(analyzed.strategy(), List.copyOf(all));
    }
}