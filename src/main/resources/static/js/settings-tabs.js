/*
 * Areas for the Configurations panel.
 *
 * Everything used to sit on one page, so reaching Server Lifecycle meant scrolling
 * past AI, Editor, Polyglot, Security, Account and the tour.
 *
 * Filtering is CSS-driven on purpose: this sets data-active-group on the container
 * and stylesheet rules hide the rest. An earlier version hid every card from JS and
 * revealed one, which meant any failure left the panel blank - the worst possible
 * outcome for a settings screen. With no attribute set, nothing is hidden, so a
 * broken or unloaded script shows all settings rather than none.
 */
const SettingsTabs = (() => {
  'use strict';

  const REMEMBER = 'arima.settings.area';
  const DEFAULT = 'ai';
  let wired = false;

  const container = () => document.getElementById('settings-view-config');

  function groups() {
    const c = container();
    if (!c) return [];
    return Array.from(new Set(
      Array.from(c.querySelectorAll('.card[data-settings-group]'))
           .map(el => el.dataset.settingsGroup)));
  }

  function show(group) {
    const c = container();
    if (!c) return;
    if (!groups().includes(group)) group = groups()[0] || DEFAULT;

    c.setAttribute('data-active-group', group);

    document.querySelectorAll('#settings-tabs .st-tab').forEach(t => {
      const on = t.dataset.group === group;
      t.classList.toggle('active', on);
      t.setAttribute('aria-selected', on ? 'true' : 'false');
    });

    try { localStorage.setItem(REMEMBER, group); } catch (_) {}

    // Start at the top: a tab switch should not land mid-way down the previous area.
    const scroller = c.closest('.tab-content') || c;
    if (scroller && scroller.scrollTop > 0) scroller.scrollTop = 0;
  }

  /**
   * Switch to whichever area owns a given control, so a deep link such as
   * SettingsNav.show('config','ai-provider-picker') lands on a visible card.
   */
  function revealFor(elementId) {
    const el = document.getElementById(elementId);
    const card = el && el.closest('.card[data-settings-group]');
    if (!card) return false;
    show(card.dataset.settingsGroup);
    return true;
  }

  function init() {
    const bar = document.getElementById('settings-tabs');
    const c = container();
    if (!bar || !c) return;

    if (!wired) {
      bar.addEventListener('click', e => {
        const tab = e.target.closest('.st-tab');
        if (tab) { e.preventDefault(); show(tab.dataset.group); }
      });
      wired = true;
    }

    let start = DEFAULT;
    try {
      const saved = localStorage.getItem(REMEMBER);
      if (saved && groups().includes(saved)) start = saved;
    } catch (_) {}
    show(start);
  }

  return { init, show, revealFor, groups };
})();

document.addEventListener('DOMContentLoaded', () => SettingsTabs.init());
window.SettingsTabs = SettingsTabs;
