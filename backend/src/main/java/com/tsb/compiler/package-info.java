/**
 * The TSL compiler: lexer, recursive-descent + Pratt parser, AST (sealed
 * records), semantic analysis, diagnostics, and the Pine Script code
 * generator. Pure Java — no Spring, no I/O, no database. Depends on nothing
 * outside {@code java.*} and {@code com.tsb.common}. This purity is what
 * makes it unit-testable at speed and reusable by both the web layer and
 * the competition worker. (Roadmap §7)
 */
package com.tsb.compiler;
