package com.tsb.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The TSL parser: tokens in, AST + diagnostics out.
 *
 * <p><b>Two techniques, one parser:</b>
 * <ul>
 *   <li><b>Recursive descent</b> for structure — one method per grammar rule
 *       ({@link #strategyDecl()}, {@link #ruleDecl()}, {@link #ifStmt()}...).
 *       The call stack mirrors the grammar, which is why each method can
 *       produce a precise "expected X after Y" message.</li>
 *   <li><b>Pratt loop</b> (precedence climbing) for expressions — every
 *       operator has a binding power (see {@link #infixLeftPower}); one loop
 *       in {@link #expression(int)} resolves all precedence and
 *       associativity. Adding an operator = adding a table row.</li>
 * </ul>
 *
 * <p><b>Panic-mode recovery:</b> an impossible token produces a
 * {@link Diagnostic}, then a private {@link ParseError} unwinds out of the
 * broken construct, and {@link #synchronize()} skips ahead to a safe
 * landmark (let / rule / IF / '}') and resumes. One compile therefore
 * reports every structural problem, without cascading nonsense errors from
 * inside a broken construct. ParseError NEVER escapes this class —
 * exceptions are internal control flow; the diagnostics list is the API.
 *
 * <p>Single-use: one Parser per token stream, like the Lexer.
 */
public final class Parser {

    /**
     * Everything one parse produces. {@code strategy} is empty when the
     * source was too broken to build a top-level node at all; even then,
     * {@code diagnostics} says why. If {@code hasErrors()}, any AST present
     * is best-effort and must not be executed.
     */
    public record ParseResult(
            Optional<StrategyAst.StrategyDecl> strategy,
            List<Diagnostic> diagnostics
    ) {
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(Diagnostic::isError);
        }
    }

    /** Internal unwinding signal for panic-mode recovery. Never escapes. */
    private static final class ParseError extends RuntimeException {
        ParseError() {
            super(null, null, false, false); // no stack trace: control flow only
        }
    }

    private final List<Token> tokens;
    private int pos = 0;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public Parser(List<Token> tokens) {
        this.tokens = Objects.requireNonNull(tokens);
        if (tokens.isEmpty() || !tokens.get(tokens.size() - 1).is(TokenType.EOF)) {
            throw new IllegalArgumentException(
                    "token stream must end with EOF (the lexer guarantees this)");
        }
    }

    // ═════════════════════════ Structure (recursive descent) ═══════════

    /** Entry point: parses one whole strategy file. */
    public ParseResult parse() {
        try {
            StrategyAst.StrategyDecl strategy = strategyDecl();
            if (!peek().is(TokenType.EOF)) {
                error("PAR008", "unexpected content after the closing '}' of the strategy",
                        peek().span());
            }
            return new ParseResult(Optional.of(strategy), List.copyOf(diagnostics));
        } catch (ParseError fatal) {
            // Top-level shape was unrecoverable (e.g. no 'strategy' keyword).
            return new ParseResult(Optional.empty(), List.copyOf(diagnostics));
        }
    }

    /** strategy := "strategy" STRING "{" member* "}" */
    private StrategyAst.StrategyDecl strategyDecl() {
        Token start = expect(TokenType.STRATEGY, "PAR001",
                "a strategy file must start with:  strategy \"Name\" { ... }");
        Token name = expect(TokenType.STRING, "PAR001",
                "expected the strategy name in quotes after 'strategy'");
        expect(TokenType.LBRACE, "PAR001",
                "expected '{' after the strategy name");

        List<StrategyAst.ConfigEntry> config = new ArrayList<>();
        List<StrategyAst.LetDecl> lets = new ArrayList<>();
        List<StrategyAst.RuleDecl> rules = new ArrayList<>();

        // Members may appear in any order (superset principle: the parser
        // accepts it; semantic analysis owns any ordering rules). Each
        // member parses inside its own try so one broken member cannot take
        // down its siblings — this is where multi-error reporting comes from.
        while (!peek().is(TokenType.RBRACE) && !peek().is(TokenType.EOF)) {
            try {
                if (peek().is(TokenType.LET)) {
                    lets.add(letDecl());
                } else if (peek().is(TokenType.RULE)) {
                    rules.add(ruleDecl());
                } else if (peek().is(TokenType.IDENT)) {
                    config.add(configEntry());
                } else {
                    error("PAR002", "expected a config entry, 'let', or 'rule' here, found "
                            + describe(peek()), peek().span());
                    throw new ParseError();
                }
            } catch (ParseError recoverable) {
                synchronize();
            }
        }

        Token end = expect(TokenType.RBRACE, "PAR001",
                "expected '}' to close the strategy");
        return new StrategyAst.StrategyDecl(
                name.stringValue(), config, lets, rules,
                start.span().merge(end.span()));
    }

    /** configEntry := IDENT "=" expr */
    private StrategyAst.ConfigEntry configEntry() {
        Token key = advance(); // known IDENT from the dispatch above
        expect(TokenType.ASSIGN, "PAR001",
                "expected '=' after config key '" + key.lexeme() + "'");
        Expr value = expression(0);
        return new StrategyAst.ConfigEntry(key.lexeme(), value,
                key.span().merge(value.span()));
    }

    /** letDecl := "let" IDENT "=" expr */
    private StrategyAst.LetDecl letDecl() {
        Token start = advance(); // LET
        Token name = expect(TokenType.IDENT, "PAR001",
                "expected a variable name after 'let'");
        expect(TokenType.ASSIGN, "PAR001",
                "expected '=' after 'let " + name.lexeme() + "'");
        Expr value = expression(0);
        return new StrategyAst.LetDecl(name.lexeme(), value,
                start.span().merge(value.span()));
    }

    /** ruleDecl := "rule" IDENT "{" ifStmt+ "}" */
    private StrategyAst.RuleDecl ruleDecl() {
        Token start = advance(); // RULE
        Token name = expect(TokenType.IDENT, "PAR001",
                "expected a rule name after 'rule'");
        expect(TokenType.LBRACE, "PAR001",
                "expected '{' after 'rule " + name.lexeme() + "'");

        List<StrategyAst.IfStmt> body = new ArrayList<>();
        boolean recoveredInBody = false;
        while (peek().is(TokenType.IF)) {
            try {
                body.add(ifStmt());
            } catch (ParseError recoverable) {
                recoveredInBody = true;
                synchronize();
            }
        }
        // Cascade suppression: if an IF inside this rule already failed, the
        // body being empty is fallout from THAT error, not a second mistake
        // by the user — reporting "rule is empty" on top would be noise.
        if (body.isEmpty() && !recoveredInBody) {
            error("PAR005", "rule '" + name.lexeme()
                            + "' is empty — a rule needs at least one IF statement",
                    name.span());
        }
        Token end = expect(TokenType.RBRACE, "PAR001",
                "expected '}' to close rule '" + name.lexeme()
                        + "' (rules contain only IF statements)");
        return new StrategyAst.RuleDecl(name.lexeme(), body,
                start.span().merge(end.span()));
    }

    /** ifStmt := "IF" expr "THEN" action ["ELSE" action] */
    private StrategyAst.IfStmt ifStmt() {
        Token start = advance(); // IF
        Expr condition = expression(0);
        expect(TokenType.THEN, "PAR001",
                "expected 'THEN' after the IF condition");
        StrategyAst.Action thenAction = action();

        Optional<StrategyAst.Action> elseAction = Optional.empty();
        if (match(TokenType.ELSE)) {
            elseAction = Optional.of(action());
        }
        Span span = start.span().merge(
                elseAction.map(StrategyAst.Action::span)
                        .orElse(thenAction.span()));
        return new StrategyAst.IfStmt(condition, thenAction, elseAction, span);
    }

    /** action := BUY sizing | SELL sizing | SET target "=" expr */
    private StrategyAst.Action action() {
        Token t = peek();
        return switch (t.type()) {
            case BUY -> {
                advance();
                StrategyAst.Sizing sizing = sizing("BUY");
                yield new StrategyAst.Action.Buy(sizing,
                        t.span().merge(sizing.span()));
            }
            case SELL -> {
                advance();
                StrategyAst.Sizing sizing = sizing("SELL");
                yield new StrategyAst.Action.Sell(sizing,
                        t.span().merge(sizing.span()));
            }
            case SET -> {
                advance();
                StrategyAst.SetTarget target = setTarget();
                expect(TokenType.ASSIGN, "PAR001",
                        "expected '=' after SET " + target);
                Expr value = expression(0);
                yield new StrategyAst.Action.Set(target, value,
                        t.span().merge(value.span()));
            }
            default -> {
                error("PAR004", "expected an action (BUY, SELL, or SET) here, found "
                        + describe(t), t.span());
                throw new ParseError();
            }
        };
    }

    private StrategyAst.SetTarget setTarget() {
        if (match(TokenType.STOPLOSS)) return StrategyAst.SetTarget.STOPLOSS;
        if (match(TokenType.TAKEPROFIT)) return StrategyAst.SetTarget.TAKEPROFIT;
        if (match(TokenType.TRAILING)) return StrategyAst.SetTarget.TRAILING;
        error("PAR004", "expected STOPLOSS, TAKEPROFIT, or TRAILING after SET, found "
                + describe(peek()), peek().span());
        throw new ParseError();
    }

    /** sizing := "ALL" | "qty" "=" expr ["OF" (EQUITY | POSITION)] */
    private StrategyAst.Sizing sizing(String verb) {
        if (peek().is(TokenType.ALL)) {
            Token all = advance();
            return new StrategyAst.Sizing.All(all.span());
        }
        Token qty = expect(TokenType.QTY, "PAR004",
                "expected a size after " + verb + " — either '" + verb
                        + " ALL' or '" + verb + " qty = ...'");
        expect(TokenType.ASSIGN, "PAR001", "expected '=' after 'qty'");
        Expr amount = expression(0);

        if (match(TokenType.OF)) {
            StrategyAst.Sizing.Base base;
            if (match(TokenType.EQUITY)) {
                base = StrategyAst.Sizing.Base.EQUITY;
            } else if (match(TokenType.POSITION)) {
                base = StrategyAst.Sizing.Base.POSITION;
            } else {
                error("PAR006", "expected EQUITY or POSITION after 'OF', found "
                        + describe(peek()), peek().span());
                throw new ParseError();
            }
            return new StrategyAst.Sizing.PercentOf(amount, base,
                    qty.span().merge(previous().span()));
        }
        return new StrategyAst.Sizing.Quantity(amount,
                qty.span().merge(amount.span()));
    }

    // ═════════════════════════ Expressions (Pratt) ═════════════════════

    /**
     * The Pratt loop. {@code minPower} is the weakest operator the caller is
     * willing to let grab the expression built so far. Left-associativity
     * comes from each operator's RIGHT power being one above its LEFT power:
     * after {@code 1 - 2}, a following '-' (left power 7) is NOT >= the
     * pending right power 8, so the loop closes {@code (1-2)} first.
     */
    Expr expression(int minPower) {
        Expr lhs = prefix();

        while (true) {
            Token op = peek();

            // Postfix lookback: tightest of all (power 13).
            if (op.is(TokenType.LBRACKET) && 13 >= minPower) {
                advance();
                lhs = lookbackSuffix(lhs);
                continue;
            }

            int leftPower = infixLeftPower(op.type());
            if (leftPower < minPower) {
                break; // next operator too weak to grab lhs — done here
            }
            advance();
            Expr rhs = expression(leftPower + 1); // +1 => left-associative
            lhs = new Expr.Binary(binaryOp(op.type()), lhs, rhs,
                    lhs.span().merge(rhs.span()));
        }
        return lhs;
    }

    /** Prefix position: literals, references, calls, grouping, unary ops. */
    private Expr prefix() {
        Token t = advance();
        return switch (t.type()) {
            case NUMBER -> new Expr.NumberLit(t.numberValue(), t.span());
            case PERCENT -> new Expr.PercentLit(t.numberValue(), t.span());
            case TIMEFRAME -> new Expr.TimeframeLit(t.stringValue(), t.span());
            case STRING -> new Expr.StringLit(t.stringValue(), t.span());

            case OPEN -> new Expr.PriceRef(Expr.PriceField.OPEN, t.span());
            case HIGH -> new Expr.PriceRef(Expr.PriceField.HIGH, t.span());
            case LOW -> new Expr.PriceRef(Expr.PriceField.LOW, t.span());
            case CLOSE -> new Expr.PriceRef(Expr.PriceField.CLOSE, t.span());
            case VOLUME -> new Expr.PriceRef(Expr.PriceField.VOLUME, t.span());

            case IDENT -> peek().is(TokenType.LPAREN) ? call(t)
                    : new Expr.VarRef(t.lexeme(), t.span());

            case LPAREN -> {
                Expr inner = expression(0);
                expect(TokenType.RPAREN, "PAR001",
                        "expected ')' to close the parenthesis");
                yield inner; // span of inner is fine; parens add no meaning
            }

            // Unary minus binds tighter than * (power 11): -a * b == (-a) * b.
            case MINUS -> {
                Expr operand = expression(11);
                yield new Expr.Unary(Expr.UnaryOp.NEG, operand,
                        t.span().merge(operand.span()));
            }
            // NOT sits between AND (3) and comparison (5):
            // NOT a < b  parses as  NOT (a < b);  NOT a AND b as (NOT a) AND b.
            case NOT -> {
                Expr operand = expression(5);
                yield new Expr.Unary(Expr.UnaryOp.NOT, operand,
                        t.span().merge(operand.span()));
            }

            default -> {
                error("PAR002", "expected an expression here, found " + describe(t),
                        t.span());
                throw new ParseError();
            }
        };
    }

    /** call := IDENT "(" [expr {"," expr}] ")" — IDENT already consumed. */
    private Expr call(Token name) {
        advance(); // LPAREN
        List<Expr> args = new ArrayList<>();
        if (!peek().is(TokenType.RPAREN)) {
            do {
                args.add(expression(0));
            } while (match(TokenType.COMMA));
        }
        Token close = expect(TokenType.RPAREN, "PAR001",
                "expected ')' to close the arguments of " + name.lexeme() + "(...)");
        return new Expr.Call(name.lexeme(), args,
                name.span().merge(close.span()));
    }

    /**
     * lookback := "[" NUMBER "]" — '[' already consumed. The offset must be
     * a non-negative integer literal; there is deliberately NO syntax for a
     * negative or computed offset, which is a structural part of the
     * "strategies cannot read the future" guarantee (roadmap §8).
     */
    private Expr lookbackSuffix(Expr target) {
        Token offsetTok = peek();
        if (!offsetTok.is(TokenType.NUMBER)
                || offsetTok.numberValue() != Math.floor(offsetTok.numberValue())) {
            error("PAR003", "a lookback offset must be a non-negative whole number, "
                    + "e.g. CLOSE[1] — found " + describe(offsetTok)
                    + " (peeking into the future is not a thing)", offsetTok.span());
            throw new ParseError();
        }
        advance();
        Token close = expect(TokenType.RBRACKET, "PAR001",
                "expected ']' after the lookback offset");
        return new Expr.Lookback(target, (int) offsetTok.numberValue(),
                target.span().merge(close.span()));
    }

    /** Left binding power per infix operator; -1 = not an infix operator. */
    private static int infixLeftPower(TokenType t) {
        return switch (t) {
            case OR -> 1;
            case AND -> 3;
            case LT, GT, LE, GE, EQ_EQ, BANG_EQ -> 5;
            case PLUS, MINUS -> 7;
            case STAR, SLASH -> 9;
            default -> -1;
        };
    }

    private static Expr.BinaryOp binaryOp(TokenType t) {
        return switch (t) {
            case OR -> Expr.BinaryOp.OR;
            case AND -> Expr.BinaryOp.AND;
            case LT -> Expr.BinaryOp.LT;
            case GT -> Expr.BinaryOp.GT;
            case LE -> Expr.BinaryOp.LE;
            case GE -> Expr.BinaryOp.GE;
            case EQ_EQ -> Expr.BinaryOp.EQ;
            case BANG_EQ -> Expr.BinaryOp.NEQ;
            case PLUS -> Expr.BinaryOp.ADD;
            case MINUS -> Expr.BinaryOp.SUB;
            case STAR -> Expr.BinaryOp.MUL;
            case SLASH -> Expr.BinaryOp.DIV;
            default -> throw new IllegalStateException("not a binary operator: " + t);
        };
    }

    // ═════════════════════════ Recovery & machinery ════════════════════

    /**
     * Panic-mode landing strip: after a ParseError, skip tokens until one
     * that plausibly starts a new construct, then resume. Chosen landmarks:
     * member starters (let/rule), statement starter (IF), and '}' so a
     * broken last member still lets the strategy close.
     */
    private void synchronize() {
        while (!peek().is(TokenType.EOF)) {
            switch (peek().type()) {
                case LET, RULE, IF, RBRACE -> {
                    return;
                }
                default -> advance();
            }
        }
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token previous() {
        return tokens.get(pos - 1);
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (!t.is(TokenType.EOF)) {
            pos++;
        }
        return t;
    }

    private boolean match(TokenType type) {
        if (peek().is(type)) {
            advance();
            return true;
        }
        return false;
    }

    /** Consume the expected token or diagnose + unwind. */
    private Token expect(TokenType type, String code, String message) {
        if (peek().is(type)) {
            return advance();
        }
        error(code, message + " (found " + describe(peek()) + ")", peek().span());
        throw new ParseError();
    }

    /** Human-friendly token description for error messages. */
    private static String describe(Token t) {
        return t.is(TokenType.EOF) ? "end of input" : "'" + t.lexeme() + "'";
    }

    private void error(String code, String message, Span span) {
        diagnostics.add(Diagnostic.error(code, message, span));
    }
}