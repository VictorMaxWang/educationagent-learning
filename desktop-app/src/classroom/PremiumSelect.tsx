import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import './premium-select.css';

export interface PremiumOption<T extends string | number> { value: T; label: string; detail?: string; mark?: string; group?: string }
export interface PremiumSelectProps<T extends string | number> {
  label: string; value: T; options: PremiumOption<T>[]; onChange(value: T): void; disabled?: boolean; mark?: string;
}

/** 一个可键盘操作的选择边界：开合时管理焦点，选项只提交稳定值，描述与学段分组不进入请求。 */
export default function PremiumSelect<T extends string | number>({ label, value, options, onChange, disabled = false, mark = '选' }: PremiumSelectProps<T>) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const root = useRef<HTMLDivElement | null>(null);
  const trigger = useRef<HTMLButtonElement | null>(null);
  const list = useRef<HTMLDivElement | null>(null);
  const current = options.find(option => option.value === value);
  const close = (restoreFocus = false) => { setOpen(false); if (restoreFocus) trigger.current?.focus(); };
  const show = (edge?: 'first' | 'last') => {
    if (disabled || !options.length) return;
    setActive(edge === 'first' ? 0 : edge === 'last' ? options.length - 1 : Math.max(0, options.findIndex(option => option.value === value))); setOpen(true);
  };
  const choose = (index: number) => { const option = options[index]; if (!option) return; onChange(option.value); close(true); };
  useEffect(() => {
    if (!open) return;
    list.current?.focus();
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);
  useEffect(() => { if (disabled) setOpen(false); }, [disabled]);
  useEffect(() => { if (open) document.getElementById(`${id}-option-${active}`)?.scrollIntoView({ block: 'nearest' }); }, [active, open, id]);
  const keydown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Tab') { close(true); return; }
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); return; }
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); choose(active); return; }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setActive(index => (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length); return; }
    if (event.key === 'Home' || event.key === 'End') { event.preventDefault(); setActive(event.key === 'Home' ? 0 : options.length - 1); return; }
    if (event.key.length === 1) {
      const offset = options.slice(active + 1).concat(options.slice(0, active + 1)).findIndex(option => option.label.toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase()));
      if (offset >= 0) setActive((active + 1 + offset) % options.length);
    }
  };
  return <div ref={root} className={`premium-select${open ? ' is-open' : ''}${disabled ? ' is-disabled' : ''}`}>
    <span id={`${id}-label`} className="ps-label">{label}</span>
    <button ref={trigger} type="button" className="ps-trigger" disabled={disabled} aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? `${id}-list` : undefined} aria-labelledby={`${id}-label ${id}-value`} onClick={() => open ? close() : show()} onKeyDown={event => { if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); show(event.key === 'ArrowUp' ? 'last' : undefined); } }}><span className="ps-mark" aria-hidden="true">{current?.mark || mark}</span><span className="ps-selected"><strong id={`${id}-value`}>{current?.label || '请选择'}</strong>{current?.detail && <small>{current.detail}</small>}</span><span className="ps-chevron" aria-hidden="true">⌄</span></button>
    {open && <div ref={list} id={`${id}-list`} role="listbox" className="ps-list" tabIndex={-1} aria-labelledby={`${id}-label`} aria-activedescendant={`${id}-option-${active}`} onKeyDown={keydown}>{options.map((option, index) => <div key={String(option.value)}>{option.group && (index === 0 || option.group !== options[index - 1].group) && <div className="ps-group-heading" aria-hidden="true">{option.group}</div>}<div role="option" id={`${id}-option-${index}`} aria-selected={value === option.value} className={`ps-option${active === index ? ' is-focused' : ''}`} onPointerMove={() => setActive(index)} onClick={() => choose(index)}><span className="ps-option-mark" aria-hidden="true">{option.mark || mark}</span><span><strong>{option.label}</strong>{option.detail && <small>{option.detail}</small>}</span>{value === option.value && <span className="ps-check" aria-hidden="true">✓</span>}</div></div>)}</div>}
  </div>;
}
