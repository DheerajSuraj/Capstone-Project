package com.tsb.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Developer CLI: compile a {@code .tsl} file and show the result.
 *
 * <pre>
 *   java -cp target/classes com.tsb.compiler.TslCli examples/rsi.tsl
 *   java -cp target/classes com.tsb.compiler.TslCli examples/rsi.tsl --tokens
 * </pre>
 *
 * <p>Prints diagnostics with the offending source line and a caret underline
 * — the same rendering the web API will later serialise as JSON for the
 * Blockly frontend to display. Exit code 0 on a clean compile, 1 otherwise,
 * so this can gate CI once the golden-file tests arrive.
 *
 * <p>This class is the ONLY place in the compiler package that touches
 * {@code java.io}; the pipeline itself (Lexer/Parser/...) stays pure
 * functions of a String, which is what keeps it trivially testable.
 */
public final class TslCli {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: TslCli <file.tsl> [--tokens]");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        boolean showTokens = args.length > 1 && args[1].equals("--tokens");

        String source = Files.readString(file);
        List<String> sourceLines = source.lines().toList();

        // ── Lex ─────────────────────────────────────────────────────────
        Lexer.LexResult lexed = new Lexer(source).scan();
        if (showTokens) {
            System.out.println("── tokens ──────────────────────────────────");
            lexed.tokens().forEach(t -> System.out.println("  " + t));
        }
        lexed.diagnostics().forEach(d -> render(d, sourceLines));

        // ── Parse ───────────────────────────────────────────────────────
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        parsed.diagnostics().forEach(d -> render(d, sourceLines));

        boolean failed = lexed.hasErrors() || parsed.hasErrors();
        if (failed || parsed.strategy().isEmpty()) {
            long errorCount = lexed.diagnostics().stream().filter(Diagnostic::isError).count()
                    + parsed.diagnostics().stream().filter(Diagnostic::isError).count();
            System.out.println("\ncompilation FAILED with " + errorCount + " error(s)");
            System.exit(1);
        }

        // ── Summary ─────────────────────────────────────────────────────
        StrategyAst.StrategyDecl s = parsed.strategy().get();
        System.out.println("── strategy \"" + s.name() + "\" ─── OK ─────────");

        System.out.println("config:");
        for (StrategyAst.ConfigEntry c : s.config()) {
            System.out.println("  " + c.key() + " = " + AstPrinter.print(c.value()));
        }
        System.out.println("lets:");
        for (StrategyAst.LetDecl let : s.lets()) {
            System.out.println("  " + let.name() + " = " + AstPrinter.print(let.value()));
        }
        System.out.println("rules:");
        for (StrategyAst.RuleDecl rule : s.rules()) {
            System.out.println("  " + rule.name() + ":");
            for (StrategyAst.IfStmt stmt : rule.body()) {
                System.out.println("    IF   " + AstPrinter.print(stmt.condition()));
                System.out.println("    THEN " + describe(stmt.thenAction()));
                stmt.elseAction().ifPresent(a ->
                        System.out.println("    ELSE " + describe(a)));
            }
        }
    }

    /** Human line for an action — exhaustive over the sealed Action. */
    private static String describe(StrategyAst.Action action) {
        return switch (action) {
            case StrategyAst.Action.Buy b -> "BUY " + describe(b.sizing());
            case StrategyAst.Action.Sell sll -> "SELL " + describe(sll.sizing());
            case StrategyAst.Action.Set set ->
                    "SET " + set.target() + " = " + AstPrinter.print(set.value());
        };
    }

    private static String describe(StrategyAst.Sizing sizing) {
        return switch (sizing) {
            case StrategyAst.Sizing.All ignored -> "ALL";
            case StrategyAst.Sizing.Quantity q ->
                    "qty = " + AstPrinter.print(q.amount());
            case StrategyAst.Sizing.PercentOf p ->
                    "qty = " + AstPrinter.print(p.percent()) + " OF " + p.base();
        };
    }

    /**
     * Renders one diagnostic with its source line and a caret underline:
     * <pre>
     * error[PAR001] 11:22: expected 'THEN' after the IF condition (found 'BUY')
     *    11 |         IF rsi < 30 BUY ALL
     *       |                     ^~~
     * </pre>
     */
    private static void render(Diagnostic d, List<String> sourceLines) {
        String severity = d.severity() == Diagnostic.Severity.ERROR ? "error" : "warning";
        Span span = d.span();
        System.out.printf("%s[%s] %d:%d: %s%n",
                severity, d.code(), span.startLine(), span.startCol(), d.message());

        if (span.startLine() <= sourceLines.size()) {
            String line = sourceLines.get(span.startLine() - 1);
            String lineNo = String.format("%4d", span.startLine());
            System.out.println(lineNo + " | " + line);

            int width = span.endLine() == span.startLine()
                    ? Math.max(1, span.endCol() - span.startCol())
                    : Math.max(1, line.length() - span.startCol() + 1);
            System.out.println("     | "
                    + " ".repeat(span.startCol() - 1)
                    + "^" + "~".repeat(Math.max(0, width - 1)));
        }
    }

    private TslCli() {
    }
}