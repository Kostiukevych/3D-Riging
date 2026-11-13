package com.example.webview

import android.content.Context
import org.json.JSONObject

object Mesh2MotionTranslator {

  private const val DICTIONARY_ASSET = "i18n/ru.json"

  fun loadDictionaryJson(context: Context): String {
    return runCatching {
        context.assets.open(DICTIONARY_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
          val raw = reader.readText()
          // Validate JSON syntax before injecting into WebView
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
        for (var i = 0; i < keys.length; i++) {
          lowerMap.set(keys[i].trim().toLowerCase(), DICT[keys[i]]);
        }

        function translateString(str) {
          if (!str) return str;
          var leading = str.match(/^\s*/)[0];
          var trailing = str.match(/\s*$/)[0];
          var core = str.trim();
          if (!core) return str;

          if (Object.prototype.hasOwnProperty.call(DICT, core)) {
            return leading + DICT[core] + trailing;
          }
          var lower = core.toLowerCase();
          if (lowerMap.has(lower)) {
            return leading + lowerMap.get(lower) + trailing;
          }
          return str;
        }

        function translateElementAttributes(el) {
          if (!el || el.nodeType !== 1) return;
          var attrs = ['placeholder', 'title', 'aria-label'];
          for (var i = 0; i < attrs.length; i++) {
            var attr = attrs[i];
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
        }

        function translateSubtree(root) {
          if (!root) return;
          if (root.nodeType === 3) {
            var p = root.parentElement;
            if (p && (p.tagName === 'SCRIPT' || p.tagName === 'STYLE' || p.tagName === 'CODE')) return;
            var translated = translateString(root.nodeValue);
            if (translated !== root.nodeValue) {
              root.nodeValue = translated;
            }
            return;
          }
          if (root.nodeType !== 1) return;
          translateElementAttributes(root);

          var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
          var node;
          while ((node = walker.nextNode())) {
            if (node.nodeType === 3) {
              var parent = node.parentElement;
              if (parent && (parent.tagName === 'SCRIPT' || parent.tagName === 'STYLE' || parent.tagName === 'CODE')) continue;
              var t = translateString(node.nodeValue);
              if (t !== node.nodeValue) {
                node.nodeValue = t;
              }
            } else if (node.nodeType === 1) {
              translateElementAttributes(node);
            }
          }
        }

        translateSubtree(document.body || document.documentElement);

        if (window.__m2mTranslatorInstalled) return;
        window.__m2mTranslatorInstalled = true;

        var pending = false;
        var observer = new MutationObserver(function(mutations) {
          if (pending) return;
          pending = true;
          requestAnimationFrame(function() {
            pending = false;
            observer.disconnect();
            try {
              translateSubtree(document.body || document.documentElement);
            } finally {
              if (document.body) {
                observer.observe(document.body, {
                  childList: true,
                  subtree: true,
                  characterData: true
                });
              }
            }
          });
        });

        if (document.body) {
          observer.observe(document.body, {
            childList: true,
            subtree: true,
            characterData: true
          });
        }
      })();
    """.trimIndent()
  }
}
