// Datchart link page (specs/0001-widget-recipe.md, "Link page").
// Decodes the widget recipe from the URL fragment on the device and either
// explains how to get the app (phone) or shows a QR of this same link (desktop).
// No network requests: the fragment never leaves the browser.
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);

  function b64urlToBytes(s) {
    const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  async function inflateRaw(bytes) {
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return new Response(stream).text();
  }

  async function decodeRecipe(fragment) {
    const recipe = JSON.parse(await inflateRaw(b64urlToBytes(fragment)));
    if (!recipe || typeof recipe !== 'object' || !recipe.source || typeof recipe.source.url !== 'string') {
      throw new Error('not a widget recipe');
    }
    return recipe;
  }

  function isPhone() {
    return /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
  }

  function renderQr(text) {
    // Error correction M when it stays small enough to scan off a screen, else L.
    let qr = null;
    for (const level of ['M', 'L']) {
      qr = qrcode(0, level);
      qr.addData(text, 'Byte');
      qr.make();
      if (qr.getModuleCount() <= 117) break; // version 25
    }
    $('qr').innerHTML = qr.createSvgTag({ cellSize: 4, margin: 4, scalable: true });
  }

  function hostOf(url) {
    try { return new URL(url).host; } catch (e) { return url; }
  }

  async function main() {
    const fragment = location.hash.slice(1);
    if (!fragment) {
      $('status').textContent = 'This link has no widget in it. Datchart links are made by your agent and end in #… .';
      return;
    }
    let recipe;
    try {
      recipe = await decodeRecipe(fragment);
    } catch (e) {
      $('status').textContent = 'This link is damaged or incomplete. Ask your agent for a new one.';
      $('status').className = 'error';
      return;
    }
    $('status').hidden = true;
    $('w-title').textContent = String(recipe.title || recipe.id || '(untitled)');
    $('w-kind').textContent = String(recipe.kind || '?');
    $('w-server').textContent = hostOf(recipe.source.url);
    $('w-tool').textContent = String(recipe.source.tool || '?');
    $('w-json').textContent = JSON.stringify(recipe, null, 2);
    $('widget').hidden = false;

    if (isPhone()) {
      $('phone').hidden = false;
    } else {
      renderQr(location.href);
      $('desktop').hidden = false;
      $('copy').addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(location.href);
          $('copy').textContent = 'Copied';
        } catch (e) {
          $('copy').textContent = 'Copy failed: select the address bar';
        }
      });
    }
  }

  window.addEventListener('hashchange', () => location.reload());
  main();
})();
