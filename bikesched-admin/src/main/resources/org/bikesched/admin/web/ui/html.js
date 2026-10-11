// SPDX-License-Identifier: Apache-2.0
// Preact with htm: tagged templates instead of JSX, so the page needs no build step and no eval.
import { h, render } from './vendor/preact.module.js';
import { useCallback, useEffect, useMemo, useRef, useState } from './vendor/hooks.module.js';
import htm from './vendor/htm.module.js';

export const html = htm.bind(h);
export { render, useCallback, useEffect, useMemo, useRef, useState };
