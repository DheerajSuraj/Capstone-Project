package com.tsb.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Standard Spring Data repository — the query methods are derived from
 *  their names at startup; no implementation class exists or is needed. */
public interface SymbolRepository extends JpaRepository<Symbol, Long> {

    Optional<Symbol> findByTicker(String ticker);
}