// SPDX-License-Identifier: Apache-2.0
// A select box with a search field: typing filters the entries by substring, so hundreds of job
// names or event names stay usable.
import { RecurringIcon } from './format.js';
import { html, useEffect, useRef, useState } from './html.js';

const Chevron = () => html`
  <svg class="chevron" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M6 9l6 6 6-6"></path>
  </svg>`;

/**
 * items: [{name, count, sub, subClass, recurring}]; selected: a name or null for the "all" entry.
 * Without allLabel there is no "all" entry and emptyLabel stands in until a name is selected.
 */
export function Picker({ label, allLabel, emptyLabel, placeholder, items, selected, onSelect }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const root = useRef(null);
  const input = useRef(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    input.current?.focus();
    const onPointer = (event) => {
      if (!root.current.contains(event.target)) {
        setOpen(false);
      }
    };
    const onKey = (event) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const needle = search.trim().toLowerCase();
  const matches = items.filter((item) => item.name.toLowerCase().includes(needle));
  const total = items.reduce((sum, item) => sum + (item.count || 0), 0);

  const shown = selected || allLabel || emptyLabel;

  const pick = (name) => {
    setOpen(false);
    setSearch('');
    onSelect(name);
  };

  const option = (name, text, count, sub, subClass, recurring) => html`
    <button type="button" role="option" class="picker-option" aria-selected=${selected === name}
      onClick=${() => pick(name)}>
      <span class="picker-option-text">
        <span class="picker-option-name">${text}${recurring && html` <${RecurringIcon} />`}</span>
        ${sub && html`<span class=${`picker-option-sub ${subClass || ''}`}>${sub}</span>`}
      </span>
      <span class="count">${count}</span>
    </button>`;

  return html`
    <div class="picker" ref=${root}>
      <button type="button" class="picker-trigger" aria-haspopup="listbox" aria-expanded=${open}
        aria-label=${`${label}: ${shown}`} onClick=${() => setOpen(!open)}>
        <span class=${selected || allLabel ? 'picker-value' : 'picker-value muted'}>${shown}</span>
        <${Chevron} />
      </button>
      ${open &&
      html`<div class="picker-popup">
        <input ref=${input} type="search" class="input" placeholder=${placeholder}
          aria-label=${`Search ${label.toLowerCase()}`} value=${search}
          onInput=${(event) => setSearch(event.target.value)}
          onKeyDown=${(event) => {
            if (event.key === 'Enter' && matches.length > 0) {
              event.preventDefault();
              pick(matches[0].name);
            }
          }} />
        <div class="picker-options" role="listbox" aria-label=${label}>
          ${!needle && allLabel && option(null, allLabel, total)}
          ${matches.map((item) => option(item.name, item.name, item.count, item.sub, item.subClass, item.recurring))}
          ${matches.length === 0 && html`<div class="picker-empty">Nothing matches.</div>`}
        </div>
      </div>`}
    </div>`;
}
