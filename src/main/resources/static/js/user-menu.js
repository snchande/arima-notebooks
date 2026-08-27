/*
 * The account control: avatar, name, and the menu that drops from it.
 *
 * Rebuilt to be self-contained. Every control is bound with addEventListener rather
 * than an inline onclick, because an attribute handler resolves its identifier
 * against the global scope at click time - if the module it names is not reachable
 * there, the click silently does nothing and the button looks dead.
 *
 * The panel is closed with the standard `hidden` attribute, which browsers honour
 * with no stylesheet at all, so a stale or missing CSS file cannot leave it stuck
 * open over the page swallowing clicks meant for other controls.
 */
const UserMenu = (() => {
  'use strict';

  let open = false;
  let profile = null;

  const $ = id => document.getElementById(id);

  function initials(name) {
    return (name || '?').trim().split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase();
  }

  function paint(user) {
    profile = user || {};
    const name = profile.name || profile.firstName || 'User';
    const isLocal = (profile.authProvider || 'LOCAL') === 'LOCAL';
    const sub = isLocal ? 'local' : String(profile.authProvider).toLowerCase();

    const avatarHtml = profile.avatarUrl
      ? `<img src="${profile.avatarUrl}" alt="">`
      : initials(name);

    const set = (id, html) => { const el = $(id); if (el) el.innerHTML = html; };
    const txt = (id, t) => { const el = $(id); if (el) el.textContent = t; };

    set('acct-avatar', avatarHtml);
    set('acct-menu-avatar', avatarHtml);
    txt('acct-name', name);
    txt('acct-sub', sub);
    txt('acct-menu-name', name);
    txt('acct-menu-sub', profile.email || (isLocal ? 'Signed in with your computer account' : sub));

    const signout = $('acct-signout');
    const signin = $('acct-signin');
    if (signout) signout.hidden = isLocal;
    if (signin) signin.hidden = !isLocal ? true : true;   // local mode needs no sign-in
  }

  function setOpen(next) {
    const menu = $('acct-menu');
    const btn = $('acct-btn');
    if (!menu || !btn) return;

    open = next;
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');

    if (open) {
      menu.hidden = false;
      // Add the class on the next frame so the transition has a start state.
      requestAnimationFrame(() => menu.classList.add('open'));
    } else {
      menu.classList.remove('open');
      menu.hidden = true;
    }
  }

  const close = () => setOpen(false);

  /**
   * Every menu action, in one place. Each entry calls the module that actually
   * owns the panel, and reports to the console when one is missing rather than
   * failing silently - a menu item that quietly does nothing is the hardest kind
   * of breakage to spot.
   */
  const ACTIONS = {
    config:    () => SettingsNav.show('config'),
    ai:        () => SettingsNav.show('config', 'ai-provider-picker'),
    tutorials: () => SettingsNav.show('tutorials'),
    docs:      () => DocsPanel.show('usage'),
    help:      () => Welcome.open(),
    about:     () => AboutUI.open(),
    email:     () => UserAuth.showEmailPrompt(),
    signout:   () => UserAuth.logout(),
    signin:    () => UserAuth.showLogin(),
  };

  function init() {
    const btn = $('acct-btn');
    const menu = $('acct-menu');
    if (!btn || !menu) return;

    btn.addEventListener('click', e => {
      e.preventDefault();
      e.stopPropagation();
      setOpen(!open);
    });

    menu.addEventListener('click', e => {
      const item = e.target.closest('.acct-item');
      if (!item) return;
      e.preventDefault();
      close();
      const act = item.dataset.act;
      const run = ACTIONS[act];
      if (!run) { console.warn('[UserMenu] no action registered for', act); return; }
      // Let the menu finish closing before a panel takes over the view.
      setTimeout(() => {
        try { run(); }
        catch (e) { console.error('[UserMenu] action "' + act + '" failed:', e); }
      }, 0);
    });

    // Close on an outside click or Escape. Bound once, rather than re-registered on
    // every open, so no listener can be left behind.
    document.addEventListener('click', e => {
      if (open && !$('acct').contains(e.target)) close();
    });
    document.addEventListener('keydown', e => {
      if (open && e.key === 'Escape') close();
    });

    load();
  }

  async function load() {
    try {
      paint(await Arima.api('GET', '/user/me'));
    } catch (_) {
      paint({ name: 'User', authProvider: 'LOCAL' });
    }
  }

  return { init, paint, close, toggle: () => setOpen(!open), refresh: load };
})();

document.addEventListener('DOMContentLoaded', () => UserMenu.init());
window.UserMenu = UserMenu;
