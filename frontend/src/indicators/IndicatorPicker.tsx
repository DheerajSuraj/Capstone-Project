import { useEffect, useMemo, useRef, useState } from 'react'
import {
  PRICE_FIELDS,
  defaultArgs,
  defaultSource,
  describe,
  describeActive,
  idFor,
  loadCatalog,
  nextColor,
  numericParams,
  sourceParam,
  type ActiveIndicator,
  type IndicatorSpec,
  type PriceField,
} from './catalog'
import './indicators.css'

/**
 * The indicator picker: a button that opens a searchable list, and a legend of
 * what is currently on the chart.
 *
 * The parent owns the active list, because the chart hook needs it too.
 */
export default function IndicatorPicker({
  active,
  onChange,
}: {
  active: ActiveIndicator[]
  onChange: (next: ActiveIndicator[]) => void
}) {
  const [catalog, setCatalog] = useState<IndicatorSpec[]>([])
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [pending, setPending] = useState<IndicatorSpec | null>(null)
  const [args, setArgs] = useState<number[]>([])
  const [source, setSource] = useState<PriceField | undefined>(undefined)

  const panelRef = useRef<HTMLDivElement>(null)
  const searchRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    loadCatalog().then(setCatalog)
  }, [])

  // Focus the search when the panel opens — people usually know the name of
  // the indicator they want.
  useEffect(() => {
    if (open && !pending) searchRef.current?.focus()
  }, [open, pending])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        close()
      }
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  function close() {
    setOpen(false)
    setPending(null)
    setQuery('')
  }

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return catalog
    return catalog.filter(
      (s) =>
        s.name.toLowerCase().includes(q) || s.label.toLowerCase().includes(q),
    )
  }, [catalog, query])

  function choose(spec: IndicatorSpec) {
    // Nothing to configure — add it straight away rather than showing an
    // empty form.
    if (spec.params.length === 0) {
      addWithCompanions(spec, undefined, [])
      return
    }
    setPending(spec)
    setArgs(defaultArgs(spec))
    setSource(defaultSource(spec))
  }

  /**
   * Adds the indicator, plus anything the catalog says belongs with it. The
   * registry splits multi-output indicators into separate names, so a
   * Bollinger upper band added alone would look like a bug.
   */
  function addWithCompanions(
    spec: IndicatorSpec,
    src: PriceField | undefined,
    values: number[],
  ) {
    const additions: ActiveIndicator[] = []
    const used = active.map((a) => a.color)

    const consider = [spec, ...resolveCompanions(spec)]
    for (const s of consider) {
      // A companion may take a different number of arguments — MACD_SIGNAL
      // has a third. Fill any it does not share from its own defaults.
      const ownArgs = numericParams(s).map(
        (p, i) => values[i] ?? p.defaultNumber ?? 14,
      )
      const ownSource = sourceParam(s) ? (src ?? defaultSource(s)) : undefined
      const id = idFor(s, ownSource, ownArgs)

      if (active.some((a) => a.id === id)) continue
      if (additions.some((a) => a.id === id)) continue

      const color = nextColor([...used, ...additions.map((a) => a.color)])
      additions.push({ id, spec: s, source: ownSource, args: ownArgs, color, visible: true })
    }

    if (additions.length > 0) onChange([...active, ...additions])
    close()
  }

  function resolveCompanions(spec: IndicatorSpec): IndicatorSpec[] {
    return (spec.companions ?? [])
      .map((name) => catalog.find((s) => s.name === name))
      .filter((s): s is IndicatorSpec => Boolean(s))
  }

  function remove(id: string) {
    onChange(active.filter((a) => a.id !== id))
  }

  function toggle(id: string) {
    onChange(active.map((a) => (a.id === id ? { ...a, visible: !a.visible } : a)))
  }

  const pendingNumeric = pending ? numericParams(pending) : []
  const pendingSource = pending ? sourceParam(pending) : undefined

  const canAdd =
    pending !== null &&
    args.every((v, i) => {
      if (!Number.isFinite(v)) return false
      const p = pendingNumeric[i]
      if (p?.positiveInt) return v >= 1 && v === Math.floor(v)
      return v > 0
    })

  return (
    <div className="tsb-ind">
      <div className="tsb-ind__bar">
        <div className="tsb-ind__anchor" ref={panelRef}>
          <button
            type="button"
            className="tsb-ind__add"
            onClick={() => (open ? close() : setOpen(true))}
            aria-expanded={open}
          >
            <PlusIcon />
            Indicators
          </button>

          {open && (
            <div className="tsb-ind__panel" role="dialog" aria-label="Add indicator">
              {!pending ? (
                <>
                  <input
                    ref={searchRef}
                    className="tsb-ind__search"
                    type="text"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder="Search indicators…"
                    autoComplete="off"
                    spellCheck={false}
                  />
                  <div className="tsb-ind__list">
                    {filtered.length === 0 && (
                      <div className="tsb-ind__empty">
                        Nothing matches “{query}”.
                      </div>
                    )}
                    {filtered.map((spec) => (
                      <button
                        key={spec.name}
                        type="button"
                        className="tsb-ind__item"
                        onClick={() => choose(spec)}
                      >
                        <span className="tsb-ind__itemname">{spec.name}</span>
                        <span className="tsb-ind__itemlabel">{spec.label}</span>
                        {spec.placement === 'separate' && (
                          <span className="tsb-ind__tag">own pane</span>
                        )}
                      </button>
                    ))}
                  </div>
                </>
              ) : (
                <div className="tsb-ind__params">
                  <div className="tsb-ind__paramshead">
                    <button
                      type="button"
                      className="tsb-ind__back"
                      onClick={() => setPending(null)}
                      aria-label="Back to list"
                    >
                      ←
                    </button>
                    <div>
                      <div className="tsb-ind__itemname">{pending.name}</div>
                      <div className="tsb-ind__itemlabel">{pending.label}</div>
                    </div>
                  </div>

                  {pendingSource && (
                    <label className="tsb-ind__param">
                      <span>{pendingSource.label}</span>
                      <select
                        value={source ?? 'CLOSE'}
                        onChange={(e) => setSource(e.target.value as PriceField)}
                      >
                        {PRICE_FIELDS.map((f) => (
                          <option key={f} value={f}>
                            {f}
                          </option>
                        ))}
                      </select>
                    </label>
                  )}

                  {pendingNumeric.map((p, i) => (
                    <label key={p.name} className="tsb-ind__param">
                      <span>{p.label}</span>
                      <input
                        type="number"
                        value={args[i]}
                        min={p.positiveInt ? 1 : 0.1}
                        step={p.positiveInt ? 1 : 0.1}
                        onChange={(e) => {
                          const next = [...args]
                          next[i] = Number(e.target.value)
                          setArgs(next)
                        }}
                      />
                    </label>
                  ))}

                  {pending.companions && pending.companions.length > 0 && (
                    <p className="tsb-ind__note">
                      Adds {pending.companions.join(' and ')} as well.
                    </p>
                  )}

                  <button
                    type="button"
                    className="tsb-ind__confirm"
                    onClick={() => addWithCompanions(pending, source, args)}
                    disabled={!canAdd}
                  >
                    Add {describe(pending, source, args)}
                  </button>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Legend: what is on the chart right now. */}
        {active.map((a) => (
          <div
            key={a.id}
            className={`tsb-ind__chip${a.visible ? '' : ' tsb-ind__chip--off'}`}
          >
            <button
              type="button"
              className="tsb-ind__swatch"
              style={{
                background: a.visible ? a.color : 'transparent',
                borderColor: a.color,
              }}
              onClick={() => toggle(a.id)}
              aria-label={
                a.visible ? `Hide ${describeActive(a)}` : `Show ${describeActive(a)}`
              }
              aria-pressed={a.visible}
            />
            <span className="tsb-ind__chiptext">{describeActive(a)}</span>
            <button
              type="button"
              className="tsb-ind__remove"
              onClick={() => remove(a.id)}
              aria-label={`Remove ${describeActive(a)}`}
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}

function PlusIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <path d="M12 5v14M5 12h14" />
    </svg>
  )
}