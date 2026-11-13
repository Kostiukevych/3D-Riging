package com.example.webview

import android.content.Context
import org.json.JSONObject

object Mesh2MotionTranslator {

  private const val DICTIONARY_ASSET = "i18n/ru.json"

  fun loadDictionaryJson(context: Context): String {
    return runCatching {
        context.assets.open(DICTIONARY_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
          val raw = reader.readText()
          JSONObject(raw).toString()
        }
      }
      .getOrDefault("{}")
  }

  fun buildInjectScript(dictionaryJson: String): String {
    return """
      (function() {
        var DICT = $dictionaryJson;
        if (!DICT || typeof DICT !== 'object') return;

        var keys = Object.keys(DICT).sort(function(a, b) {
          return b.length - a.length;
        });
        if (keys.length === 0) return;

        var lowerMap = new Map();
        var phraseEntries = [];
        for (var i = 0; i < keys.length; i++) {
          var k = keys[i];
          var v = DICT[k];
          var normKey = k.trim().replace(/\s+/g, ' ').toLowerCase();
          lowerMap.set(normKey, v);
          if (normKey.length >= 4 && /^[a-z0-9][a-z0-9\s\-/.]*[a-z0-9]$/i.test(normKey)) {
            var escaped = normKey.replace(/[.*+?^${'$'}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s+');
            phraseEntries.push({
              re: new RegExp('(^|[^A-Za-z0-9_])(' + escaped + ')(?![A-Za-z0-9_])', 'gi'),
              val: v
            });
          }
        }

        function hasCyrillic(s) {
          return /[а-яА-ЯёЁ]/.test(s);
        }

        function lookupExact(core) {
          if (!core) return null;
          if (Object.prototype.hasOwnProperty.call(DICT, core)) return DICT[core];
          var norm = core.replace(/\s+/g, ' ');
          if (Object.prototype.hasOwnProperty.call(DICT, norm)) return DICT[norm];
          var lower = norm.toLowerCase();
          if (lowerMap.has(lower)) return lowerMap.get(lower);

          var punctMatch = norm.match(/^(.+?)([:…?!.]+)$/);
          if (punctMatch) {
            var stem = punctMatch[1].trim();
            var suffix = punctMatch[2];
            var stemLower = stem.toLowerCase();
            if (Object.prototype.hasOwnProperty.call(DICT, stem)) {
              return DICT[stem] + suffix;
            }
            if (lowerMap.has(stemLower)) {
              var tr = lowerMap.get(stemLower);
              return (tr.endsWith(suffix) ? tr : tr + suffix);
            }
          }
          return null;
        }

        function translateCompound(core) {
          // 1. "Step 1: Load Model" or "Step 1 - Load Model"
          var stepM = core.match(/^Step\s+(\d+)\s*([:\-–—])?\s*(.*)$/i);
          if (stepM) {
            var rest = stepM[3] ? translateCore(stepM[3]) : '';
            var sep = stepM[2] ? (stepM[2] + ' ') : '';
            return 'Шаг ' + stepM[1] + (rest ? (sep || ': ') + rest : '');
          }

          // 2. Trailing count badge in parentheses: "Export GLB (12)" or "Animations (170)"
          var parenM = core.match(/^(.+?)\s*(\(\s*\d+(?:\s*\/\s*\d+)?\s*\))$/);
          if (parenM) {
            var baseTr = lookupExact(parenM[1].trim());
            if (baseTr) return baseTr + ' ' + parenM[2];
          }

          // 3. Numeric counts: "12 animations", "4 selected", "24 bones", "1200 vertices"
          var countM = core.match(/^(\d+)\s+(animations?|selected|bones?|vertices|models?|files?)$/i);
          if (countM) {
            var unit = countM[2].toLowerCase();
            var ruUnit = unit.startsWith('anim') ? 'анимаций'
              : unit === 'selected' ? 'выбрано'
              : unit.startsWith('bone') ? 'костей'
              : unit.startsWith('vert') ? 'вершин'
              : unit.startsWith('model') ? 'моделей'
              : 'файлов';
            return countM[1] + ' ' + ruUnit;
          }

          // 4. Key: Value or A / B / C segments
          if (core.indexOf(':') > 0 && core.indexOf('://') === -1) {
            var colonIdx = core.indexOf(':');
            var left = core.slice(0, colonIdx).trim();
            var right = core.slice(colonIdx + 1).trim();
            var leftTr = lookupExact(left);
            var rightTr = right ? (lookupExact(right) || right) : '';
            if (leftTr) {
              return right ? (leftTr + ': ' + rightTr) : (leftTr + ':');
            }
          }

          if (core.indexOf('/') > 0 && core.length <= 64) {
            var parts = core.split(/\s*\/\s*/);
            if (parts.length >= 2 && parts.length <= 5) {
              var changed = false;
              var mapped = parts.map(function(p) {
                var t = lookupExact(p);
                if (t) { changed = true; return t; }
                return p;
              });
              if (changed) return mapped.join(' / ');
            }
          }

          return null;
        }

        function translateCore(core) {
          if (!core || core.length > 260) return core;
          if (hasCyrillic(core)) return core;
          // Skip pure numbers, file extensions, URLs, or hex codes
          if (/^[\d\s.,:%+\-/#()]+$/.test(core)) return core;
          if (/^(https?:\/\/|blob:|data:|\.[a-z0-9]{2,5}$)/i.test(core)) return core;

          var exact = lookupExact(core);
          if (exact) return exact;

          var compound = translateCompound(core.replace(/\s+/g, ' '));
          if (compound) return compound;

          // Longest-phrase fallback inside English UI labels/tooltips
          var out = core.replace(/\s+/g, ' ');
          var replaced = false;
          for (var i = 0; i < phraseEntries.length; i++) {
            var entry = phraseEntries[i];
            entry.re.lastIndex = 0;
            if (entry.re.test(out)) {
              entry.re.lastIndex = 0;
              out = out.replace(entry.re, function(_, prefix) {
                replaced = true;
                return prefix + entry.val;
              });
            }
          }
          return replaced ? out : core;
        }

        function translateString(str) {
          if (!str) return str;
          var leading = str.match(/^\s*/)[0];
          var trailing = str.match(/\s*$/)[0];
          var core = str.trim();
          if (!core) return str;
          var next = translateCore(core);
          if (next !== core) {
            return leading + next + trailing;
          }
          return str;
        }

        var ATTRS = ['placeholder', 'title', 'aria-label', 'data-tooltip', 'data-title', 'data-bs-original-title', 'alt'];

        function translateElementAttributes(el) {
          if (!el || el.nodeType !== 1) return;
          for (var i = 0; i < ATTRS.length; i++) {
            var attr = ATTRS[i];
            if (el.hasAttribute(attr)) {
              var val = el.getAttribute(attr);
              var next = translateString(val);
              if (next !== val) {
                el.setAttribute(attr, next);
              }
            }
          }
          if (el.tagName === 'INPUT' && (el.type === 'button' || el.type === 'submit' || el.type === 'reset')) {
            var vNext = translateString(el.value);
            if (vNext !== el.value) el.value = vNext;
          }
          if (el.tagName === 'OPTION' && el.label) {
            var lNext = translateString(el.label);
            if (lNext !== el.label) el.label = lNext;
          }
        }

        function translateSubtree(root) {
          if (!root) return;
          if (root.nodeType === 3) {
            var p = root.parentElement;
            if (p && (p.tagName === 'SCRIPT' || p.tagName === 'STYLE' || p.tagName === 'CODE' || p.tagName === 'PRE' || p.tagName === 'TEXTAREA')) return;
            var translated = translateString(root.nodeValue);
            if (translated !== root.nodeValue) {
              root.nodeValue = translated;
            }
            return;
          }
          if (root.nodeType === 1) {
            translateElementAttributes(root);
            if (root.shadowRoot) {
              translateSubtree(root.shadowRoot);
            }
          }

          var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
          var node;
          while ((node = walker.nextNode())) {
            if (node.nodeType === 3) {
              var parent = node.parentElement;
              if (parent && (parent.tagName === 'SCRIPT' || parent.tagName === 'STYLE' || parent.tagName === 'CODE' || parent.tagName === 'PRE' || parent.tagName === 'TEXTAREA')) continue;
              var t = translateString(node.nodeValue);
              if (t !== node.nodeValue) {
                node.nodeValue = t;
              }
            } else if (node.nodeType === 1) {
              translateElementAttributes(node);
              if (node.shadowRoot) {
                translateSubtree(node.shadowRoot);
              }
            }
          }
        }

        var isTranslating = false;
        function runPass() {
          if (isTranslating) return;
          isTranslating = true;
          try {
            if (document.title) {
              var titleTr = translateString(document.title);
              if (titleTr !== document.title) document.title = titleTr;
            }
            translateSubtree(document.body || document.documentElement);
          } finally {
            isTranslating = false;
          }
        }

        runPass();

        if (window.__m2mTranslatorInstalled) {
          if (window.__m2mEnsureObserver) window.__m2mEnsureObserver();
          return;
        }
        window.__m2mTranslatorInstalled = true;
        window.__m2mRunTranslate = runPass;

        var pending = false;
        function schedulePass() {
          if (pending) return;
          pending = true;
          requestAnimationFrame(function() {
            pending = false;
            if (observer) observer.disconnect();
            try {
              runPass();
            } finally {
              attachObserver();
            }
          });
        }

        var observer = new MutationObserver(function() {
          schedulePass();
        });

        function attachObserver() {
          var target = document.body || document.documentElement;
          if (!target) return;
          observer.observe(target, {
            childList: true,
            subtree: true,
            characterData: true,
            attributes: true,
            attributeFilter: ATTRS
          });
        }
        window.__m2mEnsureObserver = attachObserver;

        attachObserver();
        document.addEventListener('DOMContentLoaded', schedulePass, true);
        window.addEventListener('load', schedulePass, true);
        window.addEventListener('hashchange', schedulePass, true);
        window.addEventListener('popstate', schedulePass, true);
        document.addEventListener('click', schedulePass, true);
        document.addEventListener('change', schedulePass, true);

        setTimeout(schedulePass, 80);
        setTimeout(schedulePass, 250);
        setTimeout(schedulePass, 700);
        setTimeout(schedulePass, 1500);
      })();
    """.trimIndent()
  }
}
